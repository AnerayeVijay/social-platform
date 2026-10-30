ALTER TABLE posts ADD COLUMN font VARCHAR(10) NOT NULL DEFAULT 'sans'
    CHECK (font IN ('sans', 'serif', 'monospace'));
ALTER TABLE posts ADD COLUMN size VARCHAR(10) NOT NULL DEFAULT 'normal'
    CHECK (size IN ('small', 'normal', 'large'));
