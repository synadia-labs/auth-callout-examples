#!/usr/bin/env bash
# =============================================================================
# setup.sh — Creates the auth-users KV bucket and adds test users.
# Run this AFTER starting the NATS server, BEFORE starting the AuthCallout service.
# =============================================================================
set -e

NATS_URL="nats://localhost:4222"
AUTH_FLAGS="--user auth --password auth -s $NATS_URL"

echo "Creating auth-users KV bucket..."
nats kv add auth-users $AUTH_FLAGS

echo ""
echo "Adding test users..."

# alice / alice123
HASH_ALICE=$(echo -n "alice123" | sha256sum | awk '{print $1}')
nats kv put auth-users alice "$HASH_ALICE" $AUTH_FLAGS
echo "  alice   / alice123  -> $HASH_ALICE"

# bob / bobpass
HASH_BOB=$(echo -n "bobpass" | sha256sum | awk '{print $1}')
nats kv put auth-users bob "$HASH_BOB" $AUTH_FLAGS
echo "  bob     / bobpass   -> $HASH_BOB"

echo ""
echo "Done. Test with:"
echo "  nats pub test 'hello' --user alice --password alice123 -s $NATS_URL"
echo "  nats pub test 'hello' --user bob   --password bobpass  -s $NATS_URL"
