#!/usr/bin/env bash
# =============================================================================
# setup.sh — Decentralized AuthCallout: nsc setup + KV bucket creation
# Run once before starting the NATS server.
# =============================================================================
set -e

OPERATOR="AuthCalloutOperator"
NSC_HOME="./nsc-store"

echo "== Step 1: Create nsc operator and accounts =="
export NSC_HOME=$NSC_HOME
nsc add operator $OPERATOR
nsc add account SYS
nsc add account AUTH
nsc add account APP1

echo ""
echo "== Step 2: Create users =="
# AUTH account users
nsc add user -a AUTH auth                     # service user
nsc add user -a AUTH sentinel --bearer        # default_sentinel bearer JWT
nsc add user -a AUTH sentinel-explicit        # explicit sentinel (Scenario A)
# SYS user
nsc add user -a SYS  sys

echo ""
echo "== Step 3: Generate credentials =="
mkdir -p creds
nsc generate creds -a AUTH -n auth     > creds/auth.creds
nsc generate creds -a AUTH -n sentinel > creds/sentinel.creds
nsc generate creds -a SYS  -n sys     > creds/sys.creds
echo "Credentials saved to creds/"

echo ""
echo "== Step 4: Generate resolver config =="
nsc generate config --mem-resolver --sys-account SYS > resolver.conf
echo "resolver.conf generated"

echo ""
echo "== Step 5: Print values needed for config.properties =="
echo ""
echo "AUTH account public key (auth.decentralized.issuer.account):"
nsc describe account AUTH 2>/dev/null | grep "Account ID" | awk '{print $NF}'

echo ""
echo "APP1 account public key (auth.decentralized.default.account):"
nsc describe account APP1 2>/dev/null | grep "Account ID" | awk '{print $NF}'

echo ""
echo "AUTH signing key path (auth.decentralized.signing.key.path):"
echo "  Check: $NSC_HOME/keys/A/<prefix>/<pubkey>.nk"
nsc describe account AUTH 2>/dev/null | grep -A2 "Signing Keys" || true

echo ""
echo "== Step 6: Get sentinel JWT for nats-server.conf default_sentinel =="
echo "Paste the following JWT into nats-server.conf as default_sentinel:"
echo ""
nsc describe user -a AUTH -n sentinel --raw 2>/dev/null || echo "(run: nsc describe user -a AUTH -n sentinel --raw)"

echo ""
echo "== Step 7: Start NATS server =="
echo "  nats-server -c nats-server.conf"
echo ""
echo "== Step 8: Create KV buckets (with server running) =="
echo "  nats kv add auth-users --creds creds/auth.creds -s nats://localhost:4222"
echo "  nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "== Step 9: Add test password user =="
HASH=$(echo -n "alice123" | sha256sum | awk '{print $1}')
echo "  nats kv put auth-users alice $HASH --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "== Step 10: Add NKey client to whitelist =="
echo "  # Generate a client NKey:  nk -gen user"
echo "  # Then:  nats kv put auth-nkeys <PUBLIC_KEY> 'my-client' --creds creds/auth.creds -s nats://localhost:4222"
