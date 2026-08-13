#!/bin/bash

nats kv add auth_bucket --user auth --password auth  --no-context
nats kv put auth_bucket john $(echo -n "john_pass" | sha256sum | awk '{print $1}')  --user auth --password auth  --no-context     
nats kv put auth_bucket bruno $(echo -n "bruno_pass" | sha256sum | awk '{print $1}')  --user auth --password auth  --no-context     
nats kv put auth_bucket ana $(echo -n "ana_pass" | sha256sum | awk '{print $1}')  --user auth --password auth  --no-context     


nats kv put auth_bucket bruno $(echo -n "bruno_pass" | shasum -a 256 | awk '{print $1}') -s nats://localhost:4222 --user auth --password auth 