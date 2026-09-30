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
log "starting nginx with TLS"
docker compose up -d nginx

sleep 3
CODE=$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/v1/auth/login" \
  -X POST -H 'Content-Type: application/json' -d '{}' || echo 000)

if [ "$CODE" = "000" ]; then
  log "WARNING: HTTPS did not answer. nginx logs:"
  docker compose logs --tail=40 nginx
  exit 1
fi
log "HTTPS serving (HTTP $CODE from the API)"

# --- 4. renewal --------------------------------------------------------------
# Webroot, so renewal needs no downtime: nginx keeps serving and certbot writes the
# challenge into the directory nginx already exposes at /.well-known/acme-challenge/.
# Twice daily is what Let's Encrypt recommends — renewal only acts inside the last 30 days,
# and a single daily attempt gives one shot at a window that matters.
log "installing the renewal timer"
cat >/etc/cron.d/certbot-renew <<EOF
0 3,15 * * * root docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt -v /var/www/certbot:/var/www/certbot certbot/certbot renew --webroot -w /var/www/certbot --quiet && cd $APP_DIR && docker compose exec -T nginx nginx -s reload
EOF
chmod 644 /etc/cron.d/certbot-renew

log "done. Certificate renews automatically; expiry notices go to $EMAIL."
log "Verify the renewal path works BEFORE trusting it:"
log "  docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/www/certbot:/var/www/certbot \\"
log "    certbot/certbot renew --webroot -w /var/www/certbot --dry-run"
