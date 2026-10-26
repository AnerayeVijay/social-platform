CREATE TABLE oauth_attempts (
    digest CHAR(64) PRIMARY KEY,
    nonce VARCHAR(128) NOT NULL,
    verifier VARCHAR(128) NOT NULL,
    link_session CHAR(64),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX oauth_attempts_expiry_idx ON oauth_attempts(expires_at);
