ALTER TABLE post_images DROP CONSTRAINT post_images_media_type_check;
ALTER TABLE post_images ALTER COLUMN media_type TYPE VARCHAR(127);
ALTER TABLE post_images ADD CONSTRAINT post_images_media_type_check
    CHECK (media_type ~ '^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$');

CREATE TABLE comment_media (
    comment_id UUID PRIMARY KEY REFERENCES comments(id) ON DELETE CASCADE,
    media_type VARCHAR(127) NOT NULL
        CHECK (media_type ~ '^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$'),
    bytes BYTEA NOT NULL
);

-- Uncompressed out-of-line TOAST lets substring() fetch only the requested chunks of large originals.
-- Affects newly stored values only; existing rows are not rewritten.
ALTER TABLE post_images ALTER COLUMN bytes SET STORAGE EXTERNAL;
ALTER TABLE comment_media ALTER COLUMN bytes SET STORAGE EXTERNAL;
