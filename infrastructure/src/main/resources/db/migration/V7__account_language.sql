ALTER TABLE accounts ADD COLUMN language VARCHAR(2) NOT NULL DEFAULT 'en'
    CHECK (language IN ('en', 'mr', 'hi', 'gu', 'ta', 'ml'));
