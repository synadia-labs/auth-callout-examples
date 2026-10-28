package main

import (
	"fmt"
	"log"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/nats-io/nkeys"
)

// runClient connects to the hub as a regular NKey client (not a leaf node).
func runClient(nkeySeed, label string) {
	natsURL := getEnv("NATS_URL", "nats://localhost:4222")

	kp, err := nkeys.FromSeed([]byte(nkeySeed))
	if err != nil {
		log.Fatalf("invalid NKey seed: %v", err)
	}
	defer kp.Wipe()

	pub, err := kp.PublicKey()
	if err != nil {
		log.Fatalf("get public key: %v", err)
	}
	log.Printf("Connecting with NKey: %s (label: %s)", pub, label)

	nc, err := nats.Connect(natsURL,
		nats.Nkey(pub, func(nonce []byte) ([]byte, error) {
			return kp.Sign(nonce)
		}),
		nats.Name("authcallout-leaf-sentinel-client"),
		nats.Timeout(5*time.Second),
	)
	if err != nil {
		log.Fatalf("connect failed: %v", err)
	}
	defer nc.Close()

	fmt.Printf("Connected as NKey client %s\n", pub)
	if err := nc.Publish("test.client", []byte("hello from "+label)); err != nil {
		log.Fatalf("publish: %v", err)
	}
	fmt.Printf("Published to test.client\n")
}
