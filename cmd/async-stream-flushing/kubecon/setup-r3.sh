#!/bin/sh

nats str add r3 \
    --replicas=3 \
    --subjects='r3.*' \
    --defaults
