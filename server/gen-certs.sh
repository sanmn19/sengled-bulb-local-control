#!/usr/bin/env bash
# Setup client certs for the Sengled bridge.
set -euo pipefail

LAN_IP="${1:-}"
if [ -z "$LAN_IP" ]; then
    echo "Usage: $0 <LAN_IP>"
    exit 1
fi

BASE="${2:-certs}"
mkdir -p "$BASE"

# CA
openssl genrsa -out "$BASE/ca.key" 2048
openssl req -x509 -new -key "$BASE/ca.key" -days 3650 -out "$BASE/ca.crt" \
    -subj "/C=US/O=SengledLocal/CN=SengledLocalCA"

# Server
openssl genrsa -out "$BASE/server.key" 2048
openssl req -new -key "$BASE/server.key" -out "$BASE/server.csr" \
    -subj "/C=US/O=SengledLocal/CN=sengled.local"

cat > "$BASE/openssl-ext.cnf" <<EXT
[req]
distinguished_name = dn
[dn]
[ext]
subjectAltName = IP:$LAN_IP,DNS:sengled.local
EXT

openssl x509 -req -in "$BASE/server.csr" \
    -CA "$BASE/ca.crt" -CAkey "$BASE/ca.key" -CAcreateserial \
    -out "$BASE/server.crt" -days 3650 -sha256 \
    -extfile "$BASE/openssl-ext.cnf" -extensions ext

rm -f "$BASE/server.csr" "$BASE/openssl-ext.cnf"
echo "certs written to $BASE/"
