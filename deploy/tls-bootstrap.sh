#!/usr/bin/env bash
#
# Issues the first Let's Encrypt certificate for API_DOMAIN, then starts the stack with TLS.
# Run once, on the instance, from /opt/platform.
#
#   sudo API_DOMAIN=api.yourdomain.com ./tls-bootstrap.sh you@yourdomain.com
#
# Why this exists: nginx is configured to load a certificate that does not exist yet, so it
# will refuse to start — and certbot's webroot mode needs nginx already serving to answer the
# challenge. This breaks the loop by issuing the first certificate in STANDALONE mode, with
# port 80 free, before nginx ever starts. Renewals afterwards use webroot and need no downtime.

set -euo pipefail

DOMAIN="${API_DOMAIN:?set API_DOMAIN, e.g. API_DOMAIN=api.yourdomain.com}"
EMAIL="${1:?usage: tls-bootstrap.sh <email-for-expiry-notices>}"
APP_DIR=/opt/platform

log() { echo "[tls] $*"; }

cd "$APP_DIR"

# --- 0. sanity: does the domain actually point here? -------------------------
# Certbot will fail anyway, but its error is about authorization rather than DNS, which
# sends people looking in the wrong place. Check first and say so plainly.
PUBLIC_IP=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
RESOLVED=$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)

if [ -z "$RESOLVED" ]; then
  log "FAILED: $DOMAIN does not resolve. Add an A record pointing at $PUBLIC_IP and wait for propagation."
  exit 1
fi
if [ "$RESOLVED" != "$PUBLIC_IP" ]; then
  log "FAILED: $DOMAIN resolves to $RESOLVED but this host is $PUBLIC_IP."
  log "        Fix the A record, or wait for the old value's TTL to expire."
  exit 1
fi
log "$DOMAIN -> $PUBLIC_IP, correct"

# --- 1. free port 80 ---------------------------------------------------------
log "stopping nginx if it is running"
docker compose stop nginx 2>/dev/null || true

mkdir -p /var/www/certbot

# --- 2. issue the certificate ------------------------------------------------
# --standalone runs certbot's own web server on port 80, which is why nginx had to stop.
# Add --dry-run while experimenting: the production endpoint allows only 5 failed
# authorizations per account per hostname per hour, and hitting that limit means waiting.
log "requesting a certificate for $DOMAIN"
docker run --rm \
  -p 80:80 \
  -v /etc/letsencrypt:/etc/letsencrypt \
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  certbot/certbot certonly \
    --standalone \
    --non-interactive \
    --agree-tos \
    --email "$EMAIL" \
    -d "$DOMAIN"

if [ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
  log "FAILED: no certificate at /etc/letsencrypt/live/$DOMAIN/"
  exit 1
fi
log "certificate issued"

# --- 3. start the stack with TLS --------------------------------------------
# Delegated to deploy.sh rather than `docker compose up -d nginx`.
#
# Compose resolves the whole file even when told to start one service, and nginx declares
# depends_on: app — so starting nginx alone still needs IMAGE_TAG and both secrets. Without
# them the app image renders as "platform:" and Compose fails on an invalid reference.
# deploy.sh already fetches the secrets, sets the tag and waits for the app to answer, so
# reusing it keeps that logic in exactly one place.
TAG="${IMAGE_TAG:-$(cat "$APP_DIR/.current-tag" 2>/dev/null || echo latest)}"
log "starting the stack with TLS (image tag: $TAG)"
"$APP_DIR/deploy.sh" "$TAG"

# deploy.sh proves the app answers over HTTP on localhost. Confirm TLS specifically, from
# the public hostname, since that is the half this script is responsible for.
# curl prints 000 itself when it cannot connect, so `|| true` rather than `|| echo 000`, which
# produced "000000" and passed this check. Only a 2xx/4xx proves the request reached the API.
CODE=$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/v1/auth/login" \
  -X POST -H 'Content-Type: application/json' -d '{}') || true
CODE="${CODE:-000}"

case "$CODE" in
  [24][0-9][0-9])
    log "HTTPS serving (HTTP $CODE from the API)"
    ;;
  *)
    log "WARNING: the app is up but HTTPS on $DOMAIN did not reach it (HTTP $CODE). nginx logs:"
    docker compose logs --tail=40 nginx
    exit 1
    ;;
esac

# --- 4. renewal --------------------------------------------------------------
# A systemd timer that renews in webroot mode, so renewal needs no downtime: nginx keeps
# serving and certbot writes the challenge into the directory nginx already exposes at
# /.well-known/acme-challenge/. See tls-renewal.sh for why it is not a cron job.
"$APP_DIR/tls-renewal.sh"

log "done. Let's Encrypt sends no expiry emails, so verify the renewal path works now:"
log "  docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt \\"
log "    -v /var/www/certbot:/var/www/certbot certbot/certbot renew --webroot -w /var/www/certbot --dry-run"
