ALTER TABLE comments ADD COLUMN parent_id UUID;
ALTER TABLE comments ADD CONSTRAINT comments_post_id_id_unique UNIQUE (post_id, id);
ALTER TABLE comments ADD CONSTRAINT comments_parent_same_post
    FOREIGN KEY (post_id, parent_id) REFERENCES comments(post_id, id) ON DELETE CASCADE;
ALTER TABLE comments ADD CONSTRAINT comments_not_own_parent CHECK (parent_id <> id);
CREATE INDEX comments_thread_page_idx ON comments(post_id, parent_id, created_at, id);

CREATE TABLE comment_likes (
    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    PRIMARY KEY (comment_id, user_id)
);
