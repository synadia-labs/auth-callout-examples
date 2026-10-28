# NATS AuthCallout Asset Generator — Java

Generate complete, working NATS AuthCallout assets for a selected scenario in **Java**.

## Usage

```
/authcallout-generate-java [use-case] [output-dir]
```

`output-dir` is optional. If omitted, files are written under `generated/<use-case>/java/`
relative to the current working directory.

| use-case | Description |
|---|---|
| `kv-store` | Centralized: username/password validated against NATS KV Store (SHA-256) |
| `mysql` | Centralized: username/password validated against MySQL |
| `okta-ropc` | Centralized: username/password via Okta ROPC flow |
| `okta-token` | Centralized: JWT access token via Okta introspection |
| `decentralized` | Decentralized: nsc operator/accounts, NKey challenge-response + KV whitelist |
| `leaf-sentinel` | Hub+Leaf: leaf nodes authenticate via NKey with Ed25519 nonce verification |

---

## Instructions

The user invoked this skill with arguments: $ARGUMENTS

**Step 1 — Parse arguments.**
Extract the first word as `use-case` and the optional second word as `output-dir`. Language is fixed to **java**.
If `use-case` is missing or unrecognized, show the table above and ask before continuing.

**Step 2 — Resolve output directory.**
- If `output-dir` was provided, write all files under `<output-dir>/` (absolute or relative to CWD).
- If not provided, write files under `generated/<use-case>/java/` relative to CWD.

**Step 3 — Delegate to the unified skill.**
Read the file `ai-skills/authcallout-generate.md` using the Read tool, then follow its
instructions exactly as if the user had invoked:

```
/authcallout-generate <use-case> java
```

Use the resolved output directory from Step 2 instead of the default path.
Compile with `mvn package -q` from that directory, and print the Java Next Steps.
