drop table users_authcallout;

CREATE TABLE users_authcallout (
   username     VARCHAR(255) PRIMARY KEY,
   password_hash VARCHAR(1024) NOT NULL
);

INSERT INTO users_authcallout (username, password_hash) VALUES ('john', SHA2('john_pass', 256));   
INSERT INTO users_authcallout (username, password_hash) VALUES ('bruno', SHA2('bruno_pass', 256));   
INSERT INTO users_authcallout (username, password_hash) VALUES ('ana', SHA2('ana_pass', 256));   
