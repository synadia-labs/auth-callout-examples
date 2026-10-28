#!/usr/bin/env bash
# setup.sh — Leaf Sentinel AuthCallout: nsc setup + leaf NKey generation
set -e

OPERATOR="AuthCalloutOperator"
NSC_HOME="./nsc-store"
export NSC_HOME=$NSC_HOME

echo "== Step 1: nsc operator and accounts =="
nsc add operator $OPERATOR
nsc add account SYS
nsc add account AUTH
nsc add account APP1

echo ""
echo "== Step 2: Create users =="
nsc add user -a AUTH auth
nsc add user -a AUTH sentinel --bearer
nsc add user -a SYS  sys

echo ""
echo "== Step 3: Generate credentials =="
mkdir -p creds
nsc generate creds -a AUTH -n auth > creds/auth.creds
nsc generate creds -a SYS  -n sys  > creds/sys.creds
echo "Credentials saved to creds/"

echo ""
echo "== Step 4: Generate resolver config =="
nsc generate config --mem-resolver --sys-account SYS > resolver.conf
echo "resolver.conf generated"

echo ""
echo "== Step 5: Generate signing key =="
nk -gen account > signing.nk
echo "Signing key saved to signing.nk"
echo "Public key (for nats-server-hub.conf):"
nk -inkey signing.nk -pubout

echo ""
echo "== Step 6: Print account public keys =="
echo ""
echo "ISSUER_ACCOUNT (AUTH account public key):"
nsc describe account AUTH 2>/dev/null | grep "Account ID" | awk '{print $NF}'
echo ""
echo "DEFAULT_ACCOUNT (APP1 account public key):"
nsc describe account APP1 2>/dev/null | grep "Account ID" | awk '{print $NF}'

echo ""
echo "== Step 7: Get sentinel JWT (optional, for default_sentinel) =="
echo "Paste into nats-server-hub.conf as default_sentinel:"
echo ""
nsc describe user -a AUTH -n sentinel --raw 2>/dev/null || true

echo ""
echo "== Step 8: Generate leaf node NKey =="
echo "Run: nk -gen server"
echo "  seed (SU...) -> nats-server-leaf.conf nkey field"
echo "  public key   -> nats kv put auth-nkeys <KEY> 'leaf-node' ..."

echo ""
echo "== Step 9: Start hub and create KV bucket =="
echo "  cp resolver.conf generated/leaf-sentinel/go/"
echo "  nats-server -c generated/leaf-sentinel/go/nats-server-hub.conf"
echo "  nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222"
echo "  nats kv put auth-nkeys <LEAF_PUBLIC_KEY> 'leaf-node' --creds creds/auth.creds -s nats://localhost:4222"

echo ""
echo "== Step 10: Startup sequence (order matters!) =="
echo "  1. Hub:     nats-server -c generated/leaf-sentinel/go/nats-server-hub.conf"
echo "  2. Service: SERVICE_CREDS=creds/auth.creds SIGNING_KEY_PATH=signing.nk \\"
echo "              ISSUER_ACCOUNT=<AUTH-pub> DEFAULT_ACCOUNT=<APP1-pub> \\"
echo "              ./authcallout-leaf-sentinel service"
echo "  3. Leaf:    nats-server -c generated/leaf-sentinel/go/nats-server-leaf.conf"
