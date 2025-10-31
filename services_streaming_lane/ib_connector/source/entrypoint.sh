#!/usr/bin/env bash
set -euo pipefail

TRUST_DIR="/trust"
MERGED="${TRUST_DIR}/merged.jks"
SYS_CACERTS="${JAVA_HOME}/lib/security/cacerts"
IBKR_PEM="${TRUST_DIR}/ibkr_client_portal.pem"

install -d "${TRUST_DIR}"

echo "[ibkr-entrypoint] Rebuilding merged truststore..."
cp -f "${SYS_CACERTS}" "${MERGED}"
chmod 644 "${MERGED}"

if [ -f "${IBKR_PEM}" ]; then
	# import (idempotent)
	if ! keytool -list -keystore "${MERGED}" -storepass changeit -alias ibkr-client-portal >/dev/null 2>&1; then
		keytool -importcert -alias ibkr-client-portal \
			-file "${IBKR_PEM}" \
			-keystore "${MERGED}" -storepass changeit -noprompt
	fi
	echo "[ibkr-entrypoint] Alias 'ibkr-client-portal' present in merged.jks."
else
	echo "[ibkr-entrypoint] WARNING: ${IBKR_PEM} not found; TLS will fail for localhost:5000."
fi

# --- Diagnostics to verify match (one-time noise is fine while fixing) ---
if command -v openssl >/dev/null 2>&1; then
	echo "[ibkr-entrypoint] Server cert fingerprint (localhost:5000):"
	openssl s_client -connect localhost:5000 -servername localhost -showcerts </dev/null 2>/dev/null \
	| openssl x509 -noout -fingerprint -sha256 || true
	if [ -f "${IBKR_PEM}" ]; then
		echo "[ibkr-entrypoint] PEM fingerprint (/trust/ibkr_client_portal.pem):"
		openssl x509 -in "${IBKR_PEM}" -noout -fingerprint -sha256 || true
	fi
fi
# ------------------------------------------------------------------------

exec "$@"
