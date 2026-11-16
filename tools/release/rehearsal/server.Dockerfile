# Local rehearsal: the SSH side of the server (docs/chromite/distribution.md "Rehearsal").
# Ubuntu 24.04 like the server, user ubuntu, /srv/chromite/{site,downloads} owned by it. Key login only;
# the rehearsal key is given at start (AUTHORIZED_KEY). The host key is made once, here, and read back by
# rehearse.ps1 into its own known_hosts (no trust on first use).
FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-server \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /run/sshd \
 && ssh-keygen -A \
 && usermod -p '*' ubuntu \
 && printf 'PasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin no\n' > /etc/ssh/sshd_config.d/rehearsal.conf
COPY server-entrypoint.sh /usr/local/bin/server-entrypoint.sh
EXPOSE 22
ENTRYPOINT ["sh", "/usr/local/bin/server-entrypoint.sh"]
