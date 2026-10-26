CREATE TABLE member_follows (
    follower_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    followed_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    status VARCHAR(10) NOT NULL CHECK (status IN ('pending', 'accepted')),
    requested_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    PRIMARY KEY (follower_id, followed_id),
    CHECK (follower_id <> followed_id),
    CHECK ((status = 'pending' AND accepted_at IS NULL) OR (status = 'accepted' AND accepted_at IS NOT NULL))
);
CREATE INDEX member_follows_incoming_idx ON member_follows(followed_id, status, follower_id);
