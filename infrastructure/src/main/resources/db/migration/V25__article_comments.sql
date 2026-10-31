CREATE TABLE article_threads (
    id UUID PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE article_comments (
    id UUID PRIMARY KEY,
    thread_id UUID NOT NULL REFERENCES article_threads(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 1000),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX article_comments_thread_idx ON article_comments(thread_id, created_at, id);
