ALTER TABLE hashtags ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE hashtags h SET created_at = COALESCE(
    (SELECT MIN(p.created_at) FROM post_hashtags ph JOIN posts p ON p.id = ph.post_id WHERE ph.hashtag_id = h.id),
    h.created_at
);
