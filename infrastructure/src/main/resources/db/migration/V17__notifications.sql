CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('follow_request', 'follow_accepted', 'new_post', 'comment', 'post_like')),
    post_id UUID REFERENCES posts(id) ON DELETE CASCADE,
    comment_id UUID REFERENCES comments(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CHECK (recipient_id <> actor_id),
    CHECK (CASE kind
        WHEN 'follow_request' THEN post_id IS NULL AND comment_id IS NULL
        WHEN 'follow_accepted' THEN post_id IS NULL AND comment_id IS NULL
        WHEN 'new_post' THEN post_id IS NOT NULL AND comment_id IS NULL
        WHEN 'post_like' THEN post_id IS NOT NULL AND comment_id IS NULL
        WHEN 'comment' THEN post_id IS NOT NULL AND comment_id IS NOT NULL
    END)
);
-- One notice per event identity: repeated mutations collapse instead of duplicating.
CREATE UNIQUE INDEX notifications_event_unique
    ON notifications(recipient_id, kind, actor_id, post_id, comment_id) NULLS NOT DISTINCT;
CREATE INDEX notifications_recipient_page_idx ON notifications(recipient_id, id DESC);
CREATE INDEX notifications_recipient_unread_idx ON notifications(recipient_id) WHERE read_at IS NULL;
