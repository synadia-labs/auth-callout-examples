#!/usr/bin/env bash
# =============================================================================
# setup.sh — Leaf Sentinel AuthCallout: nsc setup + leaf NKey generation
# Same nsc setup as the decentralized scenario.
# =============================================================================
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
nsc generate creds -a AUTH -n auth     > creds/auth.creds
nsc generate creds -a SYS  -n sys     > creds/sys.creds
echo "Credentials saved to creds/"

echo ""
echo "== Step 4: Generate resolver config =="
nsc generate config --mem-resolver --sys-account SYS > resolver.conf
echo "resolver.conf generated"

echo ""
echo "== Step 5: Print config.properties values =="
echo ""
echo "auth.decentralized.issuer.account (AUTH account public key):"
nsc describe account AUTH 2>/dev/null | grep "Account ID" | awk '{print $NF}'
echo ""
echo "auth.decentralized.default.account (APP1 account public key):"
nsc describe account APP1 2>/dev/null | grep "Account ID" | awk '{print $NF}'

echo ""
echo "== Step 6: Get sentinel JWT for nats-server-hub.conf =="
echo "Paste this into nats-server-hub.conf as default_sentinel:"
echo ""
nsc describe user -a AUTH -n sentinel --raw 2>/dev/null || true

echo ""
echo "== Step 7: Generate leaf node NKey =="
echo "Run the following to create a leaf NKey seed:"
echo "  nk -gen server"
echo "  # Note the seed (SU...) → put in nats-server-leaf.conf nkey field"
echo "  # Note the public key (U...) → add to auth-nkeys KV bucket"

echo ""
echo "== Step 8: Start hub and create KV buckets =="
echo "  cp resolver.conf generated/leaf-sentinel/java/"
echo "  nats-server -c generated/leaf-sentinel/java/nats-server-hub.conf"
echo "  nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "== Step 9: Add leaf NKey to whitelist =="
echo "  nats kv put auth-nkeys <LEAF_PUBLIC_KEY> 'leaf-node' --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "== Step 10: Startup sequence =="
echo "  1. Hub:     nats-server -c generated/leaf-sentinel/java/nats-server-hub.conf"
echo "  2. Service: java -jar target/authcallout-leaf-sentinel-1.0.0.jar service config.properties"
echo "  3. Leaf:    nats-server -c generated/leaf-sentinel/java/nats-server-leaf.conf"
