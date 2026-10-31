ALTER TABLE notifications ADD COLUMN post_author_id UUID;
ALTER TABLE notifications ADD COLUMN post_visibility VARCHAR(10);
UPDATE notifications n SET post_author_id = p.author_id, post_visibility = p.visibility
FROM posts p WHERE p.id = n.post_id;
