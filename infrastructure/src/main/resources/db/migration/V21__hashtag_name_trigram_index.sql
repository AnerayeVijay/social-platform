CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX hashtags_name_trgm_idx ON hashtags USING gin (name gin_trgm_ops);
