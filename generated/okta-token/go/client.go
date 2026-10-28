package main

import (
	"fmt"
	"log"
	"time"

	"github.com/nats-io/nats.go"
)

func runClient(accessToken string) {
	natsURL := getEnv("NATS_URL", "nats://localhost:4222")

	nc, err := nats.Connect(natsURL,
		nats.Token(accessToken),
		nats.Name("authcallout-okta-token-client"),
		nats.Timeout(10*time.Second),
	)
	if err != nil {
		log.Fatalf("connect failed: %v", err)
	}
	defer nc.Close()

	fmt.Println("Connected (token accepted by Okta introspection)")
	if err := nc.Publish("test.token", []byte("hello from token client")); err != nil {
		log.Fatalf("publish: %v", err)
	}
	fmt.Println("Published to test.token")
}
