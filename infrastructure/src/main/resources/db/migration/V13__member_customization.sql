CREATE TABLE member_preferences (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    theme VARCHAR(6) NOT NULL DEFAULT 'system' CHECK (theme IN ('light', 'dark', 'system')),
    bio VARCHAR(300) NOT NULL DEFAULT ''
);

CREATE TABLE member_images (
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('avatar', 'background')),
    version UUID NOT NULL,
    image_bytes BYTEA NOT NULL CHECK (octet_length(image_bytes) <= 5242880),
    PRIMARY KEY (account_id, kind)
);
