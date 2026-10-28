CREATE DATABASE IF NOT EXISTS authcallout_db;
USE authcallout_db;

CREATE TABLE IF NOT EXISTS users (
    username     VARCHAR(255) PRIMARY KEY,
    password_hash VARCHAR(64) NOT NULL
);

-- SHA2() in MySQL produces the same SHA-256 hex as Go's crypto/sha256
INSERT INTO users (username, password_hash) VALUES
    ('alice', SHA2('alice123', 256)),
    ('bob',   SHA2('bobpass',  256))
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);
