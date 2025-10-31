#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./gen-ibkr-trust.sh [HOST:PORT]
# Examples:
#   ./gen-ibkr-trust.sh                 # defaults to localhost:5000
#   ./gen-ibkr-trust.sh 127.0.0.1:5000
#   ./gen-ibkr-trust.sh client-portal.client-portal-api:5000   # inside cluster

TARGET="${1:-localhost:5000}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PEM="${HERE}/ibkr_client_portal.pem"
JKS="${HERE}/ibkr_truststore.jks"

echo "[gen-ibkr-trust] fetching server certificate from ${TARGET} …"
# NOTE: -servername enables SNI; -showcerts prints the chain
openssl s_client -showcerts -servername "${TARGET%%:*}" -connect "${TARGET}" </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > "${PEM}"

if [[ ! -s "${PEM}" ]]; then
  echo "[gen-ibkr-trust] ERROR: could not export certificate to ${PEM}"
  exit 1
fi
echo "[gen-ibkr-trust] saved ${PEM}"

echo "[gen-ibkr-trust] creating JKS truststore …"
rm -f "${JKS}"
keytool -importcert \
  -alias ibkr-client-portal \
  -file  "${PEM}" \
  -keystore "${JKS}" \
  -storetype JKS \
  -storepass changeit \
  -noprompt

echo "[gen-ibkr-trust] verifying truststore:"
keytool -list -keystore "${JKS}" -storepass changeit -storetype JKS | sed -n '1,120p'

echo "[gen-ibkr-trust] done."
