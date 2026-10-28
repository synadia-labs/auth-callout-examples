#!/usr/bin/env bash
# setup.sh — Okta token introspection has no local setup; configure env vars.
set -e

echo "== Okta Token Introspection Setup =="
echo ""
echo "Set the following environment variables before starting the service:"
echo ""
echo "  export SIGNING_KEY_SEED=SA...          # from: nk -gen account"
echo "  export OKTA_DOMAIN=https://dev-XXXXX.okta.com"
echo "  export OKTA_CLIENT_ID=<your-client-id>"
echo "  export OKTA_CLIENT_SECRET=<your-client-secret>"
echo ""
echo "To get an access token for testing:"
echo "  # Use Okta's token endpoint, Postman, or your app's login flow"
echo "  # Then pass it to the client:"
echo "  ./authcallout-okta-token client <your-access-token>"
