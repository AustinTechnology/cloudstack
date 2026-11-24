#!/bin/bash
# Installs the Hyper-V console helper on a CloudStack management server (Ubuntu/Debian).
# Usage: sudo ./install.sh <address-the-console-proxy-reaches-this-server-on>
set -euo pipefail
address="${1:?usage: install.sh <listen address>}"
here="$(cd "$(dirname "$0")" && pwd)"
apt-get install -y --no-install-recommends xvfb x11vnc freerdp3-x11 >/dev/null
id hvconsole >/dev/null 2>&1 || useradd --system --home-dir /var/lib/hyperv-console --shell /usr/sbin/nologin hvconsole
install -d -m 0755 /usr/local/lib/hyperv-console
install -m 0644 "$here/hyperv_console_helper.py" /usr/local/lib/hyperv-console/
if [[ ! -e /etc/hyperv-console-helper.json ]]; then
    printf '{"listen_address": "%s"}\n' "$address" > /etc/hyperv-console-helper.json
    chmod 0644 /etc/hyperv-console-helper.json
fi
install -m 0644 "$here/hyperv-console-helper.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now hyperv-console-helper
systemctl --no-pager --lines=3 status hyperv-console-helper
echo "Open TCP 5900-5999 on $address to the console proxy VMs only."
