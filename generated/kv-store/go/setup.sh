#!/usr/bin/env bash
# setup.sh — Create KV bucket and seed test users
set -e

NATS_URL="${NATS_URL:-nats://localhost:4222}"
BUCKET="${KV_BUCKET:-auth-users}"

echo "== Creating KV bucket '$BUCKET' =="
nats kv add "$BUCKET" --user auth --password auth -s "$NATS_URL"

echo ""
echo "== Adding test users =="
nats kv put "$BUCKET" alice "$(echo -n "alice123" | sha256sum | awk '{print $1}')" \
    --user auth --password auth -s "$NATS_URL"
nats kv put "$BUCKET" bob "$(echo -n "bobpass" | sha256sum | awk '{print $1}')" \
    --user auth --password auth -s "$NATS_URL"

echo ""
echo "Done! Users 'alice' (alice123) and 'bob' (bobpass) added."
