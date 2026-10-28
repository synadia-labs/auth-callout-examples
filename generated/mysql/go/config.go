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
	MysqlDSN       string
	MysqlQuery     string
}

func LoadConfig() Config {
	return Config{
		NatsURL:        getEnv("NATS_URL", "nats://localhost:4222"),
		ServiceUser:    getEnv("SERVICE_USER", "auth"),
		ServicePass:    getEnv("SERVICE_PASS", "auth"),
		SigningKeySeed: mustEnv("SIGNING_KEY_SEED"),
		DefaultAccount: getEnv("DEFAULT_ACCOUNT", "APP"),
		MysqlDSN:       getEnv("MYSQL_DSN", "root:password@tcp(localhost:3306)/authcallout_db"),
		MysqlQuery:     getEnv("MYSQL_QUERY", "SELECT password_hash FROM users WHERE username = ?"),
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
