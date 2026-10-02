#!/usr/bin/env bash
#
# Runs ON the EC2 instance, invoked by the GitLab `deploy` job through SSM Send-Command.
# Install at /opt/platform/deploy.sh, owned by root, mode 755.
#
#   ./deploy.sh <image-tag>      e.g. ./deploy.sh a1b2c3d
#
# Rolling back is the same command with an older tag — which only works because the pipeline
# pushes an immutable tag per commit rather than overwriting `latest`.

set -euo pipefail

TAG="${1:?usage: deploy.sh <image-tag>}"
APP_DIR=/opt/platform
REGION="${AWS_REGION:-ap-south-2}"
IMAGE_REPO="registry.gitlab.com/featureflag/platform"

cd "$APP_DIR"

log() { echo "[deploy] $*"; }

# --- secrets -----------------------------------------------------------------
# Fetched per deploy rather than written to disk. The instance role signs these calls;
# there are no AWS credentials on the box.
log "fetching secrets from SSM"
param() {
  aws ssm get-parameter --region "$REGION" --name "$1" \
    --with-decryption --query Parameter.Value --output text
}

export JWT_SECRET=$(param /featureflag/prod/jwt-secret)
export SPRING_DATASOURCE_PASSWORD=$(param /featureflag/prod/db-password)
REGISTRY_USER=$(param /featureflag/prod/registry-user)
REGISTRY_TOKEN=$(param /featureflag/prod/registry-token)

# Consumed by docker-compose.yml as ${IMAGE_TAG}.
export IMAGE_TAG="$TAG"

# --- pull --------------------------------------------------------------------
log "pulling $IMAGE_REPO:$TAG"
echo "$REGISTRY_TOKEN" | docker login registry.gitlab.com -u "$REGISTRY_USER" --password-stdin
docker compose pull app

# Record what is running now, so a failed start can be reversed without consulting CI.
PREVIOUS_TAG=$(cat "$APP_DIR/.current-tag" 2>/dev/null || echo "none")
log "current tag: $PREVIOUS_TAG -> $TAG"

# --- restart -----------------------------------------------------------------
log "starting"
docker compose up -d

# --- verify ------------------------------------------------------------------
# There is no actuator health endpoint in this application, so readiness is inferred:
# nginx answers 502 while the app is down and something else once it is serving. Any
# HTTP status from the API path means Spring is up and routing.
# Probe over HTTPS, pinned to loopback with --resolve so the request carries the real
# hostname (correct SNI, valid certificate, no DNS dependency) while never leaving the box.
#
# It used to probe http://localhost. That worked until TLS existed; afterwards nginx answered
# the redirect itself and the check passed on a 301 without ever reaching Spring — so a deploy
# with a crash-looping application reported success. A redirect is explicitly NOT ready now.
DOMAIN=$(grep -E '^API_DOMAIN=' "$APP_DIR/.env" 2>/dev/null | cut -d= -f2-)

probe() {
  if [ -n "$DOMAIN" ]; then
    curl -s -o /dev/null -w '%{http_code}' \
      --resolve "$DOMAIN:443:127.0.0.1" \
      -X POST "https://$DOMAIN/api/v1/auth/login" \
      -H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo 000
  else
    # No domain configured yet (pre-TLS bootstrap): plain HTTP still reaches the app.
    curl -s -o /dev/null -w '%{http_code}' \
      -X POST http://localhost/api/v1/auth/login \
      -H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo 000
  fi
}

log "waiting for the application to answer${DOMAIN:+ on https://$DOMAIN}"
READY=0
for i in $(seq 1 30); do
  CODE=$(probe)

  case "$CODE" in
    # 000 no connection · 5xx nginx cannot reach the app · 3xx only nginx answered
    000|5??|3??)
      sleep 5
      ;;
    *)
      # A 4xx here is success: the request reached Spring and was rejected on its merits
      # (the empty body fails validation), which is exactly what proves the app is serving.
      log "application responding (HTTP $CODE after $((i * 5))s)"
      READY=1
      break
      ;;
  esac
done

if [ "$READY" -ne 1 ]; then
  log "FAILED: no response after 150s. Last 80 log lines:"
  docker compose logs --tail=80 app
  log "roll back with: ./deploy.sh $PREVIOUS_TAG"
  exit 1
fi

echo "$TAG" > "$APP_DIR/.current-tag"

# Old images accumulate on a 1 GB instance with limited disk; keep the box from filling up.
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true

log "deployed $TAG"
