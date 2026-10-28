package main

import (
	"bytes"
	"encoding/base64"
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
		nats.Name("authcallout-leaf-sentinel"),
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

	nkeyHandler, err := NewNKeyKvHandler(js, cfg.NKeyBucketName, cfg.DefaultAccount)
	if err != nil {
		log.Fatalf("NKey KV handler: %v", err)
	}
	log.Printf("NKey bucket: %q, default account: %q", cfg.NKeyBucketName, cfg.DefaultAccount)

	sub, err := nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
		handleAuth(msg, nkeyHandler, kp, cfg.IssuerAccount)
	})
	if err != nil {
		log.Fatalf("subscribe: %v", err)
	}
	defer sub.Unsubscribe()

	nc.Flush()
	log.Println("AuthCallout service ready — waiting for auth requests (hub port 4222, leaf port 7422)...")
	log.Println("IMPORTANT: Leaf node must connect AFTER this service is running.")

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	fmt.Println("Shutting down...")
}

func handleAuth(msg *nats.Msg, nkeyHandler *NKeyKvHandler, kp nkeys.KeyPair, issuerAccount string) {
	rc, err := natsjwt.DecodeAuthorizationRequestClaims(string(msg.Data))
	if err != nil {
		log.Printf("ERROR decode auth request: %v", err)
		return
	}
	ar := rc.AuthorizationRequest

	clientNKey := ar.ConnectOptions.Nkey
	kind := ar.ClientInformation.Kind

	if clientNKey == "" {
		sendError(msg, kp, ar.UserNkey, ar.Server.ID, issuerAccount, "NKey required")
		log.Printf("Auth DENIED — kind: %s, no NKey in connect options", kind)
		return
	}

	// CRITICAL: Verify the nonce signature BEFORE the KV lookup.
	// When default_sentinel is active, the NATS server skips its own nonce-sig check.
	// This callout is the SOLE enforcer of key possession for all NKey connections.
	if err := verifyNonce(ar, clientNKey); err != nil {
		sendError(msg, kp, ar.UserNkey, ar.Server.ID, issuerAccount, "nonce signature verification failed")
		log.Printf("Auth DENIED — kind: %s, nkey: %s, nonce verification failed: %v", kind, clientNKey, err)
		return
	}

	// KV whitelist lookup
	ok, account, authErr := nkeyHandler.Authenticate(clientNKey)
	if !ok {
		errMsg := "NKey not authorized"
		if authErr != nil {
			errMsg = authErr.Error()
		}
		sendError(msg, kp, ar.UserNkey, ar.Server.ID, issuerAccount, errMsg)
		log.Printf("Auth DENIED — kind: %s, nkey: %s, reason: %s", kind, clientNKey, errMsg)
		return
	}

	// Build decentralized JWT with IssuerAccount
	uc := natsjwt.NewUserClaims(ar.UserNkey)
	uc.Name = clientNKey
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
	responseJwt, err := resp.Encode(kp)
	if err != nil {
		log.Printf("ERROR encode response JWT: %v", err)
		return
	}

	if err := msg.Respond([]byte(responseJwt)); err != nil {
		log.Printf("ERROR respond: %v", err)
		return
	}
	log.Printf("Auth SUCCESS — kind: %s, identity: %q, account: %q", kind, clientNKey, account)
}

// verifyNonce verifies that the client holds the private key corresponding to clientNKey.
// This is the sole key-possession check when default_sentinel is active.
func verifyNonce(ar natsjwt.AuthorizationRequest, clientNKey string) error {
	if ar.ClientInformation.Nonce == "" {
		return fmt.Errorf("nonce missing from client info")
	}
	if ar.ConnectOptions.SignedNonce == "" {
		return fmt.Errorf("signed nonce missing from connect options")
	}

	kp, err := nkeys.FromPublicKey(clientNKey)
	if err != nil {
		return fmt.Errorf("invalid NKey public key: %w", err)
	}

	sig, err := base64.RawURLEncoding.DecodeString(ar.ConnectOptions.SignedNonce)
	if err != nil {
		return fmt.Errorf("decode signature: %w", err)
	}

	return kp.Verify([]byte(ar.ClientInformation.Nonce), sig)
}

func sendError(msg *nats.Msg, kp nkeys.KeyPair, userNkey, serverID, issuerAccount, errMsg string) {
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
