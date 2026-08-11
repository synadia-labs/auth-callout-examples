# NATS Auth Callout Reference Implementations

This project contains a series of examples of how to use AuthCallout in different scenarios:
+ Config based static AuthCallout configuration
+ Operator mode decentralized AuthCallout Configuration
+ Synadia Control Plane (SCP) integration via AuthCallout


## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java | jdk 23 + | (https://openjdk.org/install/) |
| maven|3.9.9+|https://maven.apache.org/download.cgi|
| nats CLI | 0.3+ | `go install github.com/nats-io/natscli/nats@latest` |
|nats nsc |v2.15.0 |https://github.com/nats-io/nsc | 
|nats-server| 2.12+| https://nats.io/download/|



## Demos

| Demo | Topic | Docs |
|---|---|---|
| [`async-stream-flushing`](cmd/async-stream-flushing/) | JetStream async stream flushing — KubeCon benchmarks (sync vs async, R1 vs R3) | [README](cmd/async-stream-flushing/kubecon/README.md) |
| [`delayed-message-scheduling`](cmd/delayed-message-scheduling/) | JetStream Message Scheduler — deferred and recurring delivery | [README](cmd/delayed-message-scheduling/cli-demos/README.md) |
| [`distributed-counter-crdt`](cmd/distributed-counter-crdt/) | JetStream distributed counter streams — CLI walkthrough and cross-domain CRDT convergence | [CLI](cmd/distributed-counter-crdt/cli-demos/README.md) · [Go](cmd/distributed-counter-crdt/crdt-convergence/README.md) |

## Repo Layout

```
cmd/
  async-stream-flushing/
    kubecon/                    # bench scripts, HTML visualizers, conf
  delayed-message-scheduling/
    cli-demos/                  # step-by-step CLI walkthrough + quick reference
    go-demos/                   # Go demo
  distributed-counter-crdt/
    cli-demos/                  # CLI walkthrough + quick reference
    crdt-convergence/           # Go demo: cross-domain CRDT convergence
      conf/                     # east + west server configs (demo-specific)
      Taskfile.yml              # start / stop / run / reset
server/
  Taskfile.yml                  # shared server lifecycle tasks (reference for per-demo Taskfiles)
  conf/
    shared.conf                 # shared auth / accounts config
    single/                     # single-node config
    cluster/                    # 3-node cluster configs
    super/                      # supercluster + leaf node configs
  bin/                          # downloaded nats-server binary (gitignored)
  data/                         # JetStream store dirs (gitignored)
  logs/                         # server logs (gitignored)
```
