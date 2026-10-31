ALTER TABLE notifications ADD COLUMN actor_name TEXT;
UPDATE notifications n SET actor_name = a.display_name FROM accounts a WHERE a.id = n.actor_id;
