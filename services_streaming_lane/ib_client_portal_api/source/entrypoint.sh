#!/usr/bin/env bash
set -euo pipefail

cd /opt/clientportal.gw

# Create the default writable dir the gateway expects
mkdir -p /opt/clientportal.gw/resources

# IMPORTANT: pass the working conf as the **second** arg (relative path, no leading ./)
exec ./bin/run.sh root/conf.yaml
