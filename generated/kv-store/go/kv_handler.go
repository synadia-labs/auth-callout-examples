package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/nats-io/nats.go"
)

// KvHandler authenticates username/password against a NATS KV bucket.
// Passwords are stored as SHA-256 hex strings.
type KvHandler struct {
	kv             nats.KeyValue
	defaultAccount string
}

func NewKvHandler(js nats.JetStreamContext, bucket, account string) (*KvHandler, error) {
	kv, err := js.KeyValue(bucket)
	if err != nil {
		return nil, fmt.Errorf("open KV bucket %q: %w", bucket, err)
	}
	return &KvHandler{kv: kv, defaultAccount: account}, nil
}

// Authenticate returns (success, account, error).
func (h *KvHandler) Authenticate(username, password string) (bool, string, error) {
	entry, err := h.kv.Get(username)
	if err != nil {
		return false, "", fmt.Errorf("user not found: %s", username)
	}
	if string(entry.Value()) == sha256Hex(password) {
		return true, h.defaultAccount, nil
	}
	return false, "", fmt.Errorf("invalid password for user: %s", username)
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}
