#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

echo "Stopping all NATS servers and leafnodes..."

# Kill all nats-server processes (includes cluster nodes and leafnodes)
pkill -f "nats-server" || true

echo "All NATS servers and leafnodes stopped."

# Optional: Clean up data directories
read -p "Do you want to clean up data directories? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Cleaning up data directories..."
    rm -rf "$SCRIPT_DIR/data/*/js"
    rm -rf "$SCRIPT_DIR/logs"
    echo "Data directories cleaned."
else
    echo "Data directories preserved."
fi
