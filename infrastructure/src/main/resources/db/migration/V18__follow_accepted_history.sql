-- Accepted notices are history: each pending->accepted transition emits exactly once (guarded by the
-- conditional UPDATE), so a later follow lifecycle between the same pair must add a new notice.
DROP INDEX notifications_event_unique;
CREATE UNIQUE INDEX notifications_event_unique
    ON notifications(recipient_id, kind, actor_id, post_id, comment_id) NULLS NOT DISTINCT
    WHERE kind <> 'follow_accepted';
