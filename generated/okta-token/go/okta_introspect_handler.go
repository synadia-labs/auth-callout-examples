package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// OktaIntrospectHandler validates a NATS auth token via Okta token introspection.
type OktaIntrospectHandler struct {
	domain         string
	clientID       string
	clientSecret   string
	introspectPath string
	defaultAccount string
	client         *http.Client
}

func NewOktaIntrospectHandler(domain, clientID, clientSecret, introspectPath, account string) *OktaIntrospectHandler {
	return &OktaIntrospectHandler{
		domain:         domain,
		clientID:       clientID,
		clientSecret:   clientSecret,
		introspectPath: introspectPath,
		defaultAccount: account,
		client:         &http.Client{Timeout: 10 * time.Second},
	}
}

func (h *OktaIntrospectHandler) AuthenticateToken(token string) (bool, string, error) {
	form := url.Values{
		"token":           {token},
		"token_type_hint": {"access_token"},
	}

	req, err := http.NewRequest("POST", h.domain+h.introspectPath,
		strings.NewReader(form.Encode()))
	if err != nil {
		return false, "", fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(h.clientID, h.clientSecret)

	resp, err := h.client.Do(req)
	if err != nil {
		return false, "", fmt.Errorf("okta introspect request: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return false, "", fmt.Errorf("parse okta response: %w", err)
	}

	active, _ := result["active"].(bool)
	if active {
		return true, h.defaultAccount, nil
	}
	return false, "", fmt.Errorf("token is inactive or invalid")
}
