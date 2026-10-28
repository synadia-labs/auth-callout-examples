package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"

	_ "github.com/go-sql-driver/mysql"
)

// MySQLHandler authenticates username/password against a MySQL table.
// Passwords are stored as SHA-256 hex strings.
type MySQLHandler struct {
	db             *sql.DB
	query          string
	defaultAccount string
}

func NewMySQLHandler(dsn, query, account string) (*MySQLHandler, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("open MySQL: %w", err)
	}
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping MySQL: %w", err)
	}
	return &MySQLHandler{db: db, query: query, defaultAccount: account}, nil
}

func (h *MySQLHandler) Authenticate(username, password string) (bool, string, error) {
	var storedHash string
	err := h.db.QueryRow(h.query, username).Scan(&storedHash)
	if err == sql.ErrNoRows {
		return false, "", fmt.Errorf("user not found: %s", username)
	}
	if err != nil {
		return false, "", fmt.Errorf("db query error: %w", err)
	}
	if storedHash == sha256Hex(password) {
		return true, h.defaultAccount, nil
	}
	return false, "", fmt.Errorf("invalid password for user: %s", username)
}

func (h *MySQLHandler) Close() {
	h.db.Close()
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}
