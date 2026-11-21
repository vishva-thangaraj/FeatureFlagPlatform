#!/usr/bin/env bash
#
# Installs (or refreshes) the systemd timer that renews every Let's Encrypt certificate on the
# instance and reloads nginx. Idempotent; tls-bootstrap.sh and tls-add-domain.sh both run it.
# Install at /opt/platform/tls-renewal.sh, owned by root, mode 755.
#
#   sudo /opt/platform/tls-renewal.sh
#
# A systemd timer, not cron: Amazon Linux 2023 ships without cronie, so there is no /etc/cron.d
# and nothing reads it. tls-bootstrap.sh used to write /etc/cron.d/certbot-renew, which failed
# after the certificate was issued, so the API's first certificate had no renewal at all.
#
# Webroot, not the standalone authenticator the first certificate was issued with: renewal runs
# while nginx holds port 80, and the port-80 server serves /var/www/certbot for every hostname.
# --webroot on the command line overrides each certificate's stored authenticator, and certbot
# records it on the next successful renewal.
#
# Let's Encrypt no longer sends expiry emails, so this timer is the only thing standing between
# a certificate and its expiry. Check it after installing:
#
#   systemctl list-timers certbot-renew.timer
#   docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt \
#     -v /var/www/certbot:/var/www/certbot certbot/certbot renew --webroot -w /var/www/certbot --dry-run

set -euo pipefail

APP_DIR=/opt/platform

log() { echo "[tls] $*"; }

cat >/etc/systemd/system/certbot-renew.service <<EOF
[Unit]
Description=Renew Let's Encrypt certificates and reload nginx
Wants=network-online.target
After=network-online.target docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=$APP_DIR
# Renews only certificates inside their last 30 days; otherwise a no-op.
ExecStart=/usr/bin/docker run --rm \\
  -v /etc/letsencrypt:/etc/letsencrypt \\
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \\
  -v /var/www/certbot:/var/www/certbot \\
  certbot/certbot renew --webroot -w /var/www/certbot --quiet
# nginx reads certificates when it loads its configuration, so a renewed one is served only
# after a reload. Skipped when ExecStart fails.
ExecStartPost=/usr/bin/docker compose exec -T nginx nginx -s reload
EOF

# Twice daily is what Let's Encrypt recommends. Persistent catches up on a run missed while the
# instance was stopped; the random delay keeps it off the top of the hour.
cat >/etc/systemd/system/certbot-renew.timer <<'EOF'
[Unit]
Description=Twice-daily Let's Encrypt renewal check

[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now certbot-renew.timer
log "renewal timer installed: $(systemctl list-timers certbot-renew.timer --no-legend --no-pager | awk '{print "next run", $1, $2, $3}')"
