package main

import (
	"log"
	"os"
)

type Config struct {
	NatsURL        string
	ServiceCreds   string
	SigningKeyPath  string
	IssuerAccount  string // AUTH account public key
	DefaultAccount string // APP1 account public key
	NKeyBucketName string
}

func LoadConfig() Config {
	return Config{
		NatsURL:        getEnv("NATS_URL", "nats://localhost:4222"),
		ServiceCreds:   mustEnv("SERVICE_CREDS"),
		SigningKeyPath:  mustEnv("SIGNING_KEY_PATH"),
		IssuerAccount:  mustEnv("ISSUER_ACCOUNT"),
		DefaultAccount: mustEnv("DEFAULT_ACCOUNT"),
		NKeyBucketName: getEnv("NKEY_BUCKET", "auth-nkeys"),
	}
}

func getEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("required env var %s not set", key)
	}
	return v
}
