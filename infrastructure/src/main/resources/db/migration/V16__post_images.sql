CREATE TABLE post_images (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 3),
    media_type VARCHAR(10) NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    bytes BYTEA NOT NULL,
    PRIMARY KEY (post_id, position)
);
