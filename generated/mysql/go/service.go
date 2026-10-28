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

	kp, err := nkeys.FromSeed([]byte(cfg.SigningKeySeed))
	if err != nil {
		log.Fatalf("invalid SIGNING_KEY_SEED: %v", err)
	}
	defer kp.Wipe()

	signingPub, _ := kp.PublicKey()
	log.Printf("Signing key: %s", signingPub)

	nc, err := nats.Connect(cfg.NatsURL,
		nats.UserInfo(cfg.ServiceUser, cfg.ServicePass),
		nats.Name("authcallout-mysql"),
	)
	if err != nil {
		log.Fatalf("connect to NATS: %v", err)
	}
	defer nc.Drain()
	log.Printf("Connected to NATS at %s", cfg.NatsURL)

	handler, err := NewMySQLHandler(cfg.MysqlDSN, cfg.MysqlQuery, cfg.DefaultAccount)
	if err != nil {
		log.Fatalf("MySQL handler: %v", err)
	}
	defer handler.Close()
	log.Printf("Connected to MySQL, default account %q", cfg.DefaultAccount)

	sub, err := nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
		handleAuth(msg, handler, kp)
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

func handleAuth(msg *nats.Msg, handler *MySQLHandler, kp nkeys.KeyPair) {
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
		uc := natsjwt.NewUserClaims(ar.UserNkey)
		uc.Name = username
		uc.Audience = account
		userJwt, err := uc.Encode(kp)
		if err != nil {
			log.Printf("ERROR encode user JWT: %v", err)
			return
		}

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
