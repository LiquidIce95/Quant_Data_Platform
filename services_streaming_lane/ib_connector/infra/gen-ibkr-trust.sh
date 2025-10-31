#!/usr/bin/env bash
set -euo pipefail

# Namespaces (override if needed)
NS_CP="${NS_CP:-client-portal-api}"
NS_IB="${NS_IB:-ib-connector}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PEM="${HERE}/ibkr_client_portal.pem"
JKS="${HERE}/ibkr_truststore.jks"

echo "[mk-truststore] client-portal ns=${NS_CP} ; ib-connector ns=${NS_IB}"

echo "[mk-truststore] port-forward svc/client-portal:5000 → 127.0.0.1:5000"
kubectl -n "${NS_CP}" port-forward svc/client-portal 5000:5000 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill ${PF_PID} >/dev/null 2>&1 || true' EXIT
sleep 1

echo "[mk-truststore] exporting server certificate …"
openssl s_client -showcerts -connect 127.0.0.1:5000 </dev/null 2>/dev/null \
	| openssl x509 -outform PEM > "${PEM}"

if [[ ! -s "${PEM}" ]]; then
	echo "[mk-truststore] ERROR: couldn’t fetch certificate from client-portal"
	exit 1
fi
echo "[mk-truststore] saved ${PEM}"

echo "[mk-truststore] creating JKS truststore …"
rm -f "${JKS}"
keytool -importcert -alias ibkr-client-portal \
	-keystore "${JKS}" -storepass changeit \
	-storetype JKS \
	-file "${PEM}" -noprompt

echo "[mk-truststore] verifying truststore …"
keytool -list -keystore "${JKS}" -storepass changeit -storetype JKS | sed -n '1,120p'


echo "[mk-truststore] done."