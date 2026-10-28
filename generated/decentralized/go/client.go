package main

import (
	"fmt"
	"log"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/nats-io/nkeys"
)

func runClient(nkeySeed string) {
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
	log.Printf("Connecting with NKey: %s", pub)

	nc, err := nats.Connect(natsURL,
		nats.Nkey(pub, func(nonce []byte) ([]byte, error) {
			return kp.Sign(nonce)
		}),
		nats.Name("authcallout-decentralized-client"),
		nats.Timeout(5*time.Second),
	)
	if err != nil {
		log.Fatalf("connect failed: %v", err)
	}
	defer nc.Close()

	fmt.Printf("Connected with NKey %s\n", pub)
	if err := nc.Publish("test.nkey", []byte("hello from NKey client")); err != nil {
		log.Fatalf("publish: %v", err)
	}
	fmt.Println("Published to test.nkey")
}
