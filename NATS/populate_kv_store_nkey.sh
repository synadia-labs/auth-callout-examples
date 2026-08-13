nats kv add auth-nkeys --creds NATS/auth.creds -s nats://localhost:4222 --no-context
nats kv put auth-nkeys UBGUVJY43PHN4TRLB7TJWEKYOQ2F5FITC7C7IF6NR3INRPRST5IVPCFF "leaf-node" --creds NATS/auth.creds -s nats://localhost:4222 --no-context


