package com.example.social.infrastructure

// One viewer parameter, bound through a scalar subquery so all read paths share the same predicate.
internal fun postVisibilitySql(post: String = "p"): String = """
    EXISTS (SELECT 1 FROM (SELECT ?::uuid AS viewer) audience WHERE
      $post.visibility='public' OR $post.author_id=audience.viewer OR
      ($post.visibility='members' AND EXISTS (SELECT 1 FROM member_follows f
        WHERE f.follower_id=audience.viewer AND f.followed_id=$post.author_id AND f.status='accepted')))
""".trimIndent()
