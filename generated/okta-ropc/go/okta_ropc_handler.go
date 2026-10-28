package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// OktaRopcHandler authenticates via the Okta Resource Owner Password Credentials flow.
type OktaRopcHandler struct {
	domain         string
	clientID       string
	clientSecret   string
	scopes         string
	tokenPath      string
	defaultAccount string
	client         *http.Client
}

func NewOktaRopcHandler(domain, clientID, clientSecret, scopes, tokenPath, account string) *OktaRopcHandler {
	return &OktaRopcHandler{
		domain:         domain,
		clientID:       clientID,
		clientSecret:   clientSecret,
		scopes:         scopes,
		tokenPath:      tokenPath,
		defaultAccount: account,
		client:         &http.Client{Timeout: 10 * time.Second},
	}
}

func (h *OktaRopcHandler) Authenticate(username, password string) (bool, string, error) {
	form := url.Values{
		"grant_type":    {"password"},
		"username":      {username},
		"password":      {password},
		"scope":         {h.scopes},
		"client_id":     {h.clientID},
		"client_secret": {h.clientSecret},
	}

	resp, err := h.client.PostForm(h.domain+h.tokenPath, form)
	if err != nil {
		return false, "", fmt.Errorf("okta request failed: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusOK {
		return false, "", fmt.Errorf("okta returned %d: %s", resp.StatusCode, string(body))
	}

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		return false, "", fmt.Errorf("parse okta response: %w", err)
	}

	if _, ok := result["access_token"]; ok {
		return true, h.defaultAccount, nil
	}
	return false, "", fmt.Errorf("no access_token in okta response")
}
