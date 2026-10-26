ALTER TABLE posts ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'public'
    CHECK (visibility IN ('public', 'members', 'private'));
