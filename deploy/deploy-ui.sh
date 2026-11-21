#!/usr/bin/env bash
#
# Runs ON the EC2 instance, invoked by the feature-platform-ui repository's deploy workflow through
# SSM Send-Command. Install at /opt/platform/deploy-ui.sh, owned by root, mode 755.
#
#   ./deploy-ui.sh <release>      e.g. ./deploy-ui.sh 1f0fcda
#
# Unpacks s3://$UI_ARTIFACT_BUCKET/ui/<release>.tar.gz into /opt/platform/ui/releases/<release>
# and points the relative symlink /opt/platform/ui/current at it. nginx serves ui/current (mounted
# at /srv/ui, see nginx/ui.conf.template), so switching is a single rename: no reload, no restart,
# and no moment where a request sees half of one release and half of another.
#
# Rolling back is the same command with an older release. One still on disk (the last few are
# kept) is switched to without downloading anything.

set -euo pipefail

RELEASE="${1:?usage: deploy-ui.sh <release>}"
APP_DIR=/opt/platform
UI_DIR="$APP_DIR/ui"
REGION="${AWS_REGION:-ap-south-2}"
# Releases kept on disk, the live one included.
KEEP=5

log() { echo "[deploy-ui] $*"; }

# The release names both a directory and an S3 key, so refuse anything that could step out of either.
case "$RELEASE" in
  .*|*[!A-Za-z0-9._-]*)
    log "FAILED: invalid release name '$RELEASE'"
    exit 1
    ;;
esac

env_value() { grep -E "^$1=" "$APP_DIR/.env" 2>/dev/null | cut -d= -f2- || true; }
BUCKET=$(env_value UI_ARTIFACT_BUCKET)
DOMAIN=$(env_value UI_DOMAIN)
: "${BUCKET:?UI_ARTIFACT_BUCKET is not set in $APP_DIR/.env}"
: "${DOMAIN:?UI_DOMAIN is not set in $APP_DIR/.env}"

mkdir -p "$UI_DIR/releases"
# nginx's workers run as an unprivileged user inside the container and must be able to traverse these.
chmod 755 "$UI_DIR" "$UI_DIR/releases"
cd "$UI_DIR"

PREVIOUS=$(readlink current 2>/dev/null || true)
PREVIOUS="${PREVIOUS#releases/}"
log "current release: ${PREVIOUS:-none} -> $RELEASE"

# --- fetch -------------------------------------------------------------------
# Unpacked into a staging directory and renamed into place only once complete, so an interrupted
# download never leaves a releases/<release> that a later run would mistake for a finished one.
if [ -f "releases/$RELEASE/index.html" ]; then
  log "release $RELEASE is already on disk"
else
  log "fetching s3://$BUCKET/ui/$RELEASE.tar.gz"
  STAGING=$(mktemp -d "$UI_DIR/releases/.staging-XXXXXX")
  trap 'rm -rf "$STAGING"' EXIT

  # The instance role signs this; there are no AWS credentials on the box.
  aws s3 cp --region "$REGION" --only-show-errors \
    "s3://$BUCKET/ui/$RELEASE.tar.gz" "$STAGING/release.tar.gz"

  mkdir "$STAGING/root"
  tar -xzf "$STAGING/release.tar.gz" -C "$STAGING/root" --no-same-owner
  if [ ! -f "$STAGING/root/index.html" ]; then
    log "FAILED: the archive has no index.html at its top level"
    exit 1
  fi
  chmod -R a+rX "$STAGING/root"

  rm -rf "releases/$RELEASE"
  mv "$STAGING/root" "releases/$RELEASE"
fi

# --- switch ------------------------------------------------------------------
# The link target is relative so that it resolves the same inside the nginx container, where this
# directory is /srv/ui. `mv -T` renames over the old link atomically; `ln -sfn` alone would
# unlink and recreate it, leaving a gap in which nginx finds nothing.
#
# The touch is for pruning, which keeps the most recently live releases. Without it a release's
# mtime is whatever tar restored from the archive: when it was built, not when it last served.
switch_to() {
  touch "releases/$1"
  ln -sfn "releases/$1" current.next
  mv -T current.next current
}

switch_to "$RELEASE"

# --- verify ------------------------------------------------------------------
# Fetch index.html through nginx over HTTPS, pinned to loopback like deploy.sh's probe (real
# hostname, so correct SNI and a valid certificate, without leaving the box), and compare it with
# the file just unpacked. A match proves the mount, the symlink and the server block all agree;
# then make sure the script it names is served too.
switch_back() {
  if [ -n "$PREVIOUS" ] && [ -d "releases/$PREVIOUS" ]; then
    switch_to "$PREVIOUS"
    log "switched back to $PREVIOUS"
  fi
}

fetch() { curl -fsS --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN$1"; }

WANT=$(sha256sum <"releases/$RELEASE/index.html")
GOT=$(fetch /index.html | sha256sum || true)
if [ "$GOT" != "$WANT" ]; then
  log "FAILED: https://$DOMAIN/index.html is not the index.html of $RELEASE"
  switch_back
  exit 1
fi

SCRIPT=$(grep -o '/assets/[^"]*\.js' "releases/$RELEASE/index.html" | head -1 || true)
if [ -n "$SCRIPT" ] && ! fetch "$SCRIPT" >/dev/null; then
  log "FAILED: https://$DOMAIN$SCRIPT is not served"
  switch_back
  exit 1
fi
log "serving $RELEASE on https://$DOMAIN"

# --- prune -------------------------------------------------------------------
# Most recently live first; the live release is never a candidate.
ls -1t releases | grep -vxF "$RELEASE" | tail -n +"$KEEP" | while read -r old; do
  rm -rf "releases/$old"
done || true

if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$RELEASE" ]; then
  log "roll back with: sudo $APP_DIR/deploy-ui.sh $PREVIOUS"
fi
log "deployed $RELEASE"
