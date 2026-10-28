package main

import (
	"fmt"
	"log"
	"time"

	"github.com/nats-io/nats.go"
)

func runClient(username, password string) {
	natsURL := getEnv("NATS_URL", "nats://localhost:4222")

	nc, err := nats.Connect(natsURL,
		nats.UserInfo(username, password),
		nats.Name("authcallout-kv-store-client"),
		nats.Timeout(5*time.Second),
	)
	if err != nil {
		log.Fatalf("connect failed for user %q: %v", username, err)
	}
	defer nc.Close()

	fmt.Printf("Connected as %q\n", username)

	// Publish a test message
	subject := fmt.Sprintf("test.%s", username)
	if err := nc.Publish(subject, []byte("hello from "+username)); err != nil {
		log.Fatalf("publish: %v", err)
	}
	fmt.Printf("Published to %q\n", subject)

	// Request/reply test (expected to time out unless a responder is running)
	msg, err := nc.Request("ping", []byte("hello"), 2*time.Second)
	if err != nil {
		fmt.Printf("Request to 'ping' (no responder expected): %v\n", err)
	} else {
		fmt.Printf("Got reply: %s\n", string(msg.Data))
	}
}
