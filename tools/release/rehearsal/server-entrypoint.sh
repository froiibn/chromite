#!/bin/sh
# Local rehearsal server start: the rehearsal key, then the state the real server had before the first
# Chromite deploy (folders owned by ubuntu, a placeholder page, an empty releases.json), then sshd.
set -eu
[ -n "${AUTHORIZED_KEY:-}" ] || { echo 'AUTHORIZED_KEY is not set' >&2; exit 1; }
install -d -o ubuntu -g ubuntu -m 700 /home/ubuntu/.ssh
printf '%s\n' "$AUTHORIZED_KEY" > /home/ubuntu/.ssh/authorized_keys
chown ubuntu:ubuntu /home/ubuntu/.ssh/authorized_keys
chmod 600 /home/ubuntu/.ssh/authorized_keys
install -d -o ubuntu -g ubuntu -m 755 /srv/chromite /srv/chromite/site /srv/chromite/downloads
if [ ! -e /srv/chromite/site/index.html ]; then
  printf '<!doctype html><title>Chromite</title><p>Chromite\n' > /srv/chromite/site/index.html
  chown ubuntu:ubuntu /srv/chromite/site/index.html; chmod 644 /srv/chromite/site/index.html
fi
if [ ! -e /srv/chromite/downloads/releases.json ]; then
  printf '{"format":1,"generated_at":%s,"files":[]}\n' "$(date -u +%s)" > /srv/chromite/downloads/releases.json
  chown ubuntu:ubuntu /srv/chromite/downloads/releases.json; chmod 644 /srv/chromite/downloads/releases.json
fi
exec /usr/sbin/sshd -D -e
