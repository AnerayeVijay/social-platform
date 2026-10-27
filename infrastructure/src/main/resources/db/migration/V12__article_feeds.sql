CREATE TABLE article_feed_sources (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error TEXT
);
CREATE INDEX article_feed_sources_owner_idx ON article_feed_sources(owner_id, created_at, id);

CREATE TABLE feed_articles (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES article_feed_sources(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    abstract TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    UNIQUE(source_id, url)
);
CREATE INDEX feed_articles_page_idx ON feed_articles(published_at DESC, id DESC);
CREATE INDEX feed_articles_source_idx ON feed_articles(source_id);
