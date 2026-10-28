-- =============================================================================
-- MySQL setup for NATS AuthCallout
-- Run: mysql -u root -p < setup.sql
-- =============================================================================

CREATE DATABASE IF NOT EXISTS authcallout_db;
USE authcallout_db;

CREATE TABLE IF NOT EXISTS users (
    username      VARCHAR(255) PRIMARY KEY,
    password_hash VARCHAR(64)  NOT NULL   -- SHA-256 hex of the password
);

-- Test users (SHA2 is MySQL's SHA-256 function)
INSERT INTO users (username, password_hash) VALUES
    ('alice', SHA2('alice123', 256)),
    ('bob',   SHA2('bobpass',  256))
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);

SELECT * FROM users;
