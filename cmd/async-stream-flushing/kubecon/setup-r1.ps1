nats str add sync `
    --replicas=1 `
    --persist-mode=default `
    "--subjects=sync.*" `
    --defaults

nats str add async `
    --replicas=1 `
    --persist-mode=async `
    "--subjects=async.*" `
    --defaults
