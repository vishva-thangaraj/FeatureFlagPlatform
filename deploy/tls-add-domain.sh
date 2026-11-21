#!/usr/bin/env bash
#
# Issues a Let's Encrypt certificate for one more hostname on an instance whose stack is already
# serving TLS. Run once per hostname, on the instance, from /opt/platform:
#
#   sudo ./tls-add-domain.sh featureflag.yourdomain.com [email]
#
# Unlike tls-bootstrap.sh this needs no downtime. The HTTP server in prod.conf.template is nginx's
# default server for port 80, so it already serves /.well-known/acme-challenge/ from
# /var/www/certbot for ANY hostname that points here, and webroot mode works before nginx has a
# server block for the new name. Issue the certificate first, then add the block that loads it:
# nginx refuses to start on a server block whose certificate does not exist.
#
# Renewal: the systemd timer from tls-renewal.sh renews every certificate under /etc/letsencrypt,
# this one included. This script (re)installs it at the end, so running it is enough either way.
#
# The email is optional. Let's Encrypt no longer sends expiry notices, and an instance that already
# has an ACME account (from tls-bootstrap.sh) keeps using it.

set -euo pipefail

DOMAIN="${1:?usage: tls-add-domain.sh <hostname> [email]}"
EMAIL="${2:-}"
APP_DIR=/opt/platform
WEBROOT=/var/www/certbot

log() { echo "[tls] $*"; }

# --- 0. sanity: does the hostname point here? --------------------------------
# Same check as tls-bootstrap.sh, for the same reason: certbot's own error for a wrong A record is
# about authorization, not DNS.
PUBLIC_IP=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
RESOLVED=$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)

if [ -z "$RESOLVED" ]; then
  log "FAILED: $DOMAIN does not resolve. Add an A record pointing at $PUBLIC_IP and wait for propagation."
  exit 1
fi
if [ "$RESOLVED" != "$PUBLIC_IP" ]; then
  log "FAILED: $DOMAIN resolves to $RESOLVED but this host is $PUBLIC_IP."
  exit 1
fi
log "$DOMAIN -> $PUBLIC_IP, correct"

# --- 1. sanity: does nginx serve the challenge directory for it? -------------
# Let's Encrypt allows only 5 failed authorizations per hostname per hour, so prove the path works
# with a file of our own before spending one.
PROBE=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')
mkdir -p "$WEBROOT/.well-known/acme-challenge"
echo "$PROBE" >"$WEBROOT/.well-known/acme-challenge/$PROBE"
SERVED=$(curl -fsS --resolve "$DOMAIN:80:127.0.0.1" \
  "http://$DOMAIN/.well-known/acme-challenge/$PROBE" 2>/dev/null || true)
rm -f "$WEBROOT/.well-known/acme-challenge/$PROBE"

if [ "$SERVED" != "$PROBE" ]; then
  log "FAILED: nginx did not serve $WEBROOT/.well-known/acme-challenge/ for $DOMAIN on port 80."
  log "        Is the stack running? (docker compose ps)"
  exit 1
fi
log "challenge directory is served for $DOMAIN"

# --- 2. issue the certificate ------------------------------------------------
if [ -n "$EMAIL" ]; then
  CONTACT=(--email "$EMAIL")
else
  CONTACT=(--register-unsafely-without-email)
fi

log "requesting a certificate for $DOMAIN"
docker run --rm \
  -v /etc/letsencrypt:/etc/letsencrypt \
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  -v "$WEBROOT:$WEBROOT" \
  certbot/certbot certonly \
    --webroot -w "$WEBROOT" \
    --non-interactive \
    --agree-tos \
    "${CONTACT[@]}" \
    -d "$DOMAIN"

if [ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
  log "FAILED: no certificate at /etc/letsencrypt/live/$DOMAIN/"
  exit 1
fi
log "certificate issued at /etc/letsencrypt/live/$DOMAIN/"

# --- 3. renewal --------------------------------------------------------------
"$APP_DIR/tls-renewal.sh"
