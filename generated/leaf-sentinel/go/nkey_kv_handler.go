package main

import (
	"fmt"

	"github.com/nats-io/nats.go"
)

// NKeyKvHandler validates NKey public keys against a NATS KV whitelist.
type NKeyKvHandler struct {
	kv             nats.KeyValue
	defaultAccount string
}

func NewNKeyKvHandler(js nats.JetStreamContext, bucket, account string) (*NKeyKvHandler, error) {
	kv, err := js.KeyValue(bucket)
	if err != nil {
		return nil, fmt.Errorf("open NKey KV bucket %q: %w", bucket, err)
	}
	return &NKeyKvHandler{kv: kv, defaultAccount: account}, nil
}

func (h *NKeyKvHandler) Authenticate(nkeyPublic string) (bool, string, error) {
	entry, err := h.kv.Get(nkeyPublic)
	if err != nil || entry == nil || len(entry.Value()) == 0 {
		return false, "", fmt.Errorf("NKey not authorized: %s", nkeyPublic)
	}
	return true, h.defaultAccount, nil
}
