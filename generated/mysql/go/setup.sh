#!/usr/bin/env bash
# setup.sh — Create MySQL database and seed test users
set -e

MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-password}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"

echo "== Running setup.sql =="
mysql -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -h "$MYSQL_HOST" < setup.sql

echo ""
echo "Done! Users 'alice' (alice123) and 'bob' (bobpass) created."
