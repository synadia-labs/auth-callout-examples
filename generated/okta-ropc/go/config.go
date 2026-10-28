package main

import (
	"log"
	"os"
)

type Config struct {
	NatsURL        string
	ServiceUser    string
	ServicePass    string
	SigningKeySeed string
	DefaultAccount string
	OktaDomain     string
	OktaClientID   string
	OktaClientSecret string
	OktaScopes     string
	OktaTokenPath  string
}

func LoadConfig() Config {
	return Config{
		NatsURL:          getEnv("NATS_URL", "nats://localhost:4222"),
		ServiceUser:      getEnv("SERVICE_USER", "auth"),
		ServicePass:      getEnv("SERVICE_PASS", "auth"),
		SigningKeySeed:   mustEnv("SIGNING_KEY_SEED"),
		DefaultAccount:   getEnv("DEFAULT_ACCOUNT", "APP"),
		OktaDomain:       mustEnv("OKTA_DOMAIN"),
		OktaClientID:     mustEnv("OKTA_CLIENT_ID"),
		OktaClientSecret: mustEnv("OKTA_CLIENT_SECRET"),
		OktaScopes:       getEnv("OKTA_SCOPES", "openid profile email"),
		OktaTokenPath:    getEnv("OKTA_TOKEN_PATH", "/oauth2/v1/token"),
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
