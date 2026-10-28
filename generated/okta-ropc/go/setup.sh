#!/usr/bin/env bash
# setup.sh — Okta ROPC has no local setup; configure env vars before running.
set -e

echo "== Okta ROPC Setup =="
echo ""
echo "Set the following environment variables before starting the service:"
echo ""
echo "  export SIGNING_KEY_SEED=SA...          # from: nk -gen account"
echo "  export OKTA_DOMAIN=https://dev-XXXXX.okta.com"
echo "  export OKTA_CLIENT_ID=<your-client-id>"
echo "  export OKTA_CLIENT_SECRET=<your-client-secret>"
echo "  export OKTA_SCOPES='openid profile email'"
echo ""
echo "In your Okta application:"
echo "  - Application type: Web or Native"
echo "  - Grant type: Resource Owner Password (ROPC) must be enabled"
echo "  - Note: Okta may deprecate ROPC — prefer okta-token (introspection) for production"
