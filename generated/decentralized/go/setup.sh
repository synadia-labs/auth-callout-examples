#!/usr/bin/env bash
# setup.sh — Decentralized AuthCallout: nsc setup + KV buckets + signing key
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
echo "Public key:"
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
echo "== Step 7: Update nats-server.conf =="
echo "Replace REPLACE_WITH_AUTH_ACCOUNT_PUBLIC_KEY with the AUTH account public key above."

echo ""
echo "== Step 8: Start server and create KV buckets =="
echo "  cp resolver.conf generated/decentralized/go/"
echo "  nats-server -c generated/decentralized/go/nats-server.conf"
echo "  nats kv add auth-users --creds creds/auth.creds -s nats://localhost:4222"
echo "  nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "== Step 9: Add test user and NKey =="
echo "  nats kv put auth-users alice \$(echo -n 'alice123' | sha256sum | awk '{print \$1}') \\"
echo "      --creds creds/auth.creds -s nats://localhost:4222"
echo ""
echo "  nk -gen user  # generates a user NKey"
echo "  nats kv put auth-nkeys <PUBLIC_KEY> 'my-client' \\"
echo "      --creds creds/auth.creds -s nats://localhost:4222"

echo ""
echo "== Step 10: Start service =="
echo "  SERVICE_CREDS=creds/auth.creds \\"
echo "  SIGNING_KEY_PATH=signing.nk \\"
echo "  ISSUER_ACCOUNT=<AUTH-account-public-key> \\"
echo "  DEFAULT_ACCOUNT=<APP1-account-public-key> \\"
echo "  ./authcallout-decentralized service"
