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
		nats.Name("authcallout-mysql-client"),
		nats.Timeout(5*time.Second),
	)
	if err != nil {
		log.Fatalf("connect failed for user %q: %v", username, err)
	}
	defer nc.Close()

	fmt.Printf("Connected as %q\n", username)

	subject := fmt.Sprintf("test.%s", username)
	if err := nc.Publish(subject, []byte("hello from "+username)); err != nil {
		log.Fatalf("publish: %v", err)
	}
	fmt.Printf("Published to %q\n", subject)
}
