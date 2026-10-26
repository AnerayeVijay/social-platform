CREATE TABLE google_identities (
    subject VARCHAR(255) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE
);
CREATE INDEX google_identities_account_idx ON google_identities(account_id);
