package main

import (
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	natsjwt "github.com/nats-io/jwt/v2"
	"github.com/nats-io/nats.go"
	"github.com/nats-io/nkeys"
)

func runService() {
	cfg := LoadConfig()

	// Load signing NKey
	kp, err := nkeys.FromSeed([]byte(cfg.SigningKeySeed))
	if err != nil {
		log.Fatalf("invalid SIGNING_KEY_SEED: %v", err)
	}
	defer kp.Wipe()

	signingPub, err := kp.PublicKey()
	if err != nil {
		log.Fatalf("get signing public key: %v", err)
	}
	log.Printf("Signing key: %s", signingPub)

	// Connect to NATS as auth service user
	nc, err := nats.Connect(cfg.NatsURL,
		nats.UserInfo(cfg.ServiceUser, cfg.ServicePass),
		nats.Name("authcallout-kv-store"),
	)
	if err != nil {
		log.Fatalf("connect to NATS: %v", err)
	}
	defer nc.Drain()
	log.Printf("Connected to NATS at %s", cfg.NatsURL)

	// Open JetStream and KV bucket
	js, err := nc.JetStream()
	if err != nil {
		log.Fatalf("JetStream: %v", err)
	}

	handler, err := NewKvHandler(js, cfg.KvBucketName, cfg.DefaultAccount)
	if err != nil {
		log.Fatalf("KV handler: %v", err)
	}
	log.Printf("Using KV bucket %q, default account %q", cfg.KvBucketName, cfg.DefaultAccount)

	// Subscribe to AuthCallout subject
	sub, err := nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
		handleAuth(msg, handler, kp)
	})
	if err != nil {
		log.Fatalf("subscribe: %v", err)
	}
	defer sub.Unsubscribe()

	if err := nc.Flush(); err != nil {
		log.Fatalf("flush: %v", err)
	}

	log.Println("AuthCallout service ready — waiting for auth requests...")

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	fmt.Println("Shutting down...")
}

func handleAuth(msg *nats.Msg, handler *KvHandler, kp nkeys.KeyPair) {
	// Decode the AuthorizationRequest JWT
	rc, err := natsjwt.DecodeAuthorizationRequestClaims(string(msg.Data))
	if err != nil {
		log.Printf("ERROR decode auth request: %v", err)
		return
	}
	ar := rc.AuthorizationRequest

	username := ar.ConnectOptions.Username
	password := ar.ConnectOptions.Password

	ok, account, authErr := handler.Authenticate(username, password)

	var responseJwt string
	if ok {
		// Build inner user JWT
		uc := natsjwt.NewUserClaims(ar.UserNkey)
		uc.Name = username
		uc.Audience = account
		userJwt, err := uc.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode user JWT: %v", err)
			return
		}

		// Build outer response JWT
		resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
		resp.Audience = ar.Server.ID
		resp.Jwt = userJwt
		responseJwt, err = resp.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode response JWT: %v", err)
			return
		}
		log.Printf("Auth SUCCESS — user: %q, account: %q", username, account)
	} else {
		errMsg := "authentication failed"
		if authErr != nil {
			errMsg = authErr.Error()
		}
		resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
		resp.Audience = ar.Server.ID
		resp.Error = errMsg
		responseJwt, err = resp.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode error response JWT: %v", err)
			return
		}
		log.Printf("Auth DENIED — user: %q, reason: %s", username, errMsg)
	}

	if err := msg.Respond([]byte(responseJwt)); err != nil {
		log.Printf("ERROR respond: %v", err)
	}
}
