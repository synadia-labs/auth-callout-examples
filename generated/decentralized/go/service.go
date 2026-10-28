package main

import (
	"bytes"
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

	// Load signing key from .nk file
	seedBytes, err := os.ReadFile(cfg.SigningKeyPath)
	if err != nil {
		log.Fatalf("read signing key from %q: %v", cfg.SigningKeyPath, err)
	}
	kp, err := nkeys.FromSeed(bytes.TrimSpace(seedBytes))
	if err != nil {
		log.Fatalf("invalid signing key seed: %v", err)
	}
	defer kp.Wipe()

	signingPub, _ := kp.PublicKey()
	log.Printf("Signing key: %s (issuer account: %s)", signingPub, cfg.IssuerAccount)

	// Connect using .creds file (decentralized operator model)
	nc, err := nats.Connect(cfg.NatsURL,
		nats.UserCredentials(cfg.ServiceCreds),
		nats.Name("authcallout-decentralized"),
	)
	if err != nil {
		log.Fatalf("connect to NATS: %v", err)
	}
	defer nc.Drain()
	log.Printf("Connected to NATS at %s", cfg.NatsURL)

	js, err := nc.JetStream()
	if err != nil {
		log.Fatalf("JetStream: %v", err)
	}

	kvHandler, err := NewKvHandler(js, cfg.KvBucketName, cfg.DefaultAccount)
	if err != nil {
		log.Fatalf("KV handler: %v", err)
	}

	nkeyHandler, err := NewNKeyKvHandler(js, cfg.NKeyBucketName, cfg.DefaultAccount)
	if err != nil {
		log.Fatalf("NKey KV handler: %v", err)
	}

	log.Printf("KV bucket: %q, NKey bucket: %q, default account: %q",
		cfg.KvBucketName, cfg.NKeyBucketName, cfg.DefaultAccount)

	sub, err := nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
		handleAuth(msg, kvHandler, nkeyHandler, kp, cfg.IssuerAccount, cfg.DefaultAccount)
	})
	if err != nil {
		log.Fatalf("subscribe: %v", err)
	}
	defer sub.Unsubscribe()

	nc.Flush()
	log.Println("AuthCallout service ready — waiting for auth requests...")

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	fmt.Println("Shutting down...")
}

func handleAuth(msg *nats.Msg, kvHandler *KvHandler, nkeyHandler *NKeyKvHandler,
	kp nkeys.KeyPair, issuerAccount, defaultAccount string) {

	rc, err := natsjwt.DecodeAuthorizationRequestClaims(string(msg.Data))
	if err != nil {
		log.Printf("ERROR decode auth request: %v", err)
		return
	}
	ar := rc.AuthorizationRequest

	var ok bool
	var account string
	var identity string
	var authErr error

	if ar.ConnectOptions.Username != "" {
		// Password-based auth
		identity = ar.ConnectOptions.Username
		ok, account, authErr = kvHandler.Authenticate(ar.ConnectOptions.Username, ar.ConnectOptions.Password)
	} else if ar.ConnectOptions.Nkey != "" {
		// NKey challenge-response auth
		identity = ar.ConnectOptions.Nkey
		ok, account, authErr = nkeyHandler.Authenticate(ar.ConnectOptions.Nkey)
	} else {
		sendDecentralizedError(msg, kp, ar.UserNkey, ar.Server.ID, issuerAccount, "no credentials provided")
		log.Printf("Auth DENIED — no credentials in connect options")
		return
	}

	var responseJwt string
	if ok {
		// Decentralized JWT: must include IssuerAccount
		uc := natsjwt.NewUserClaims(ar.UserNkey)
		uc.Name = identity
		uc.Audience = account
		uc.IssuerAccount = issuerAccount
		userJwt, err := uc.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode user JWT: %v", err)
			return
		}

		resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
		resp.Audience = ar.Server.ID
		resp.Jwt = userJwt
		resp.IssuerAccount = issuerAccount
		responseJwt, err = resp.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode response JWT: %v", err)
			return
		}
		log.Printf("Auth SUCCESS — identity: %q, account: %q", identity, account)
	} else {
		errMsg := "authentication failed"
		if authErr != nil {
			errMsg = authErr.Error()
		}
		sendDecentralizedError(msg, kp, ar.UserNkey, ar.Server.ID, issuerAccount, errMsg)
		log.Printf("Auth DENIED — identity: %q, reason: %s", identity, errMsg)
		return
	}

	if err := msg.Respond([]byte(responseJwt)); err != nil {
		log.Printf("ERROR respond: %v", err)
	}
}

func sendDecentralizedError(msg *nats.Msg, kp nkeys.KeyPair, userNkey, serverID, issuerAccount, errMsg string) {
	resp := natsjwt.NewAuthorizationResponseClaims(userNkey)
	resp.Audience = serverID
	resp.Error = errMsg
	resp.IssuerAccount = issuerAccount
	responseJwt, err := resp.Encode(kp)
	if err != nil {
		log.Printf("ERROR encode error response JWT: %v", err)
		return
	}
	if err := msg.Respond([]byte(responseJwt)); err != nil {
		log.Printf("ERROR respond: %v", err)
	}
}
