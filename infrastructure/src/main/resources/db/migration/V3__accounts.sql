CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash TEXT,
    verified BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE account_tokens (
    digest CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_tokens_expiry_idx ON account_tokens(expires_at);
