# Openly — Social Web Application and Backend API

A runnable **modular-monolith starter** for posts, hashtags, likes and comments. Uses Kotlin 2.3.21, Gradle 9.2.1, http4k 6.20.0.0, PostgreSQL 17, Flyway and HikariCP.

Dependency versions are pinned for reproducibility. The PostgreSQL JDBC driver is 42.7.14.

## Quick start

From the project root, with JDK 21 selected and Docker running:

```bash
docker compose up -d postgres mailpit
./gradlew :server:run
```

| Service | URL |
|---|---|
| Web application | http://localhost:8080 |
| Sign in / registration | http://localhost:8080/login / http://localhost:8080/register |
| JSON feed | http://localhost:8080/api/feed |
| Health check | http://localhost:8080/health |
| Development email inbox | http://localhost:8025 |

Register, open the verification email in the development inbox, confirm your email, and sign in. The web frontend and backend API run in **one server**, with separate source and test modules.

If port `5432` is already occupied, use these commands instead:

```bash
POSTGRES_PORT=5433 docker compose up -d postgres mailpit
DATABASE_URL=jdbc:postgresql://localhost:5433/social ./gradlew :server:run
```

## Project layout

```text
social-platform/
├── web/             Browser pages, forms, views, Google sign-in callbacks
├── api/             JSON endpoints and bearer authentication
├── http-common/     Shared HTTP security and cursor utilities
├── notifications/   Notification types, store port and read/list service
├── server/          Executable application and dependency wiring
├── application/     Shared use cases and storage/query interfaces
├── domain/          Business objects and invariants
└── infrastructure/  PostgreSQL, migrations, SMTP, Google, password hashing
```

Every module owns its tests under `src/test/kotlin`. Web code is in `web/src/main/kotlin/com/example/social/web`; JSON handlers are in `api/src/main/kotlin/com/example/social/api`. The entry point is `server/src/main/kotlin/com/example/social/server/Main.kt`.

**Startup command:** `./gradlew :server:run`. The `web` and `api` modules are libraries assembled by `server`; they do not have separate run tasks.

## Requirements

- JDK 21
- Docker with Compose
- Internet access on first Gradle build (to download Gradle and dependencies)

## Run locally

Run the following commands from the project root. **Openly** provides a responsive Kotlin-served web frontend and a JSON API backed by the same services and PostgreSQL database.

### 1. Check prerequisites

Make sure JDK 21 is selected and Docker is running:

```bash
java -version
docker compose version
docker info
```

### 2. Start PostgreSQL and the development inbox

```bash
docker compose up -d postgres mailpit
docker compose ps
```

Wait for PostgreSQL to report `healthy`. If it does not, inspect its logs:

```bash
docker compose logs postgres
```

### 3. Run tests and build

The included `gradlew` launcher downloads Gradle 9.2.1 on first use; a separate Gradle installation is not required.

```bash
./gradlew test
./gradlew build
```

Tests include real PostgreSQL integration tests (Testcontainers) and browser journeys (Playwright). Docker must be running. Playwright uses its managed Chromium browser, or an existing browser supplied with `BROWSER_EXECUTABLE`.

On macOS with OrbStack and Google Chrome installed:

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export BROWSER_EXECUTABLE="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
./gradlew test
./gradlew build
```

Without an existing browser, omit the two Playwright environment variables; Playwright downloads its browser on first use.

### 4. Start the web application and API

```bash
./gradlew :server:run
```

Keep this terminal open. Open **http://localhost:8080** in your browser. On startup, Flyway applies pending database migrations, preserving existing posts.

1. Choose **Create an account**, enter a display name, email, and a 12–128 character password.
2. Open the development inbox at **http://localhost:8025** and follow the verification link.
3. Confirm verification, then sign in.
4. Publish posts, like/unlike, comment, search hashtags, and edit your display name from **My profile**.
5. Open another member's profile from a post and choose **Request to follow**. The member can accept or reject it under **Follow requests**.

### Following members

The home feed has **All posts** and **Following** tabs. Following requires sign-in and shows
your own posts plus posts from members who accepted your request, newest first. Approval includes
their existing posts. Hashtag filters and pagination work within the selected tab.

Signed-in members also get **My posts**, which lists only posts you wrote (Public, Members-only,
and Private), newest first, with images, fonts, sizes, and comment links. It is about your own
posts, not who you follow, and does not change notifications. Comments stay on each post's page.

Profiles show **Request to follow**, **Cancel request**, or **Unfollow**. Signed-in feed and
profile pages link to incoming requests (with a count), followers, and following. Members can
accept/reject requests, cancel pending requests, unfollow, and remove approved followers.
Rejected or removed members may request again. Only verified accounts can participate;
archived legacy profiles cannot receive requests.

Choose **Find members** from the signed-in member navigation to search by display name.
Search matches any part of a name, ignoring case, and shows verified members other than yourself,
20 per page. Results link to profiles and include request, cancel, or unfollow controls.
Follow requests still require the other member's approval.

### Profile and appearance settings

After signing in, choose **Settings** in the header, or **Edit profile and appearance** on your
profile. You can:

- Select **Light**, **Dark**, or **System**. System follows the device's current light/dark setting.
- Upload, replace, preview, or remove a personal body background image. Only you see it, across the website.
- Upload, replace, preview, or remove a separate header background image shown in the top navigation. It is private to you, sitewide, and independent of the body background.
- Edit your display name and public bio (up to 300 characters).
- Upload, replace, or remove your public profile photo. Photos appear on your profile and wherever
  the site already displays your avatar; initials remain the fallback when there is no photo.

Preferences and images are saved in PostgreSQL and follow your account across devices. Other
members keep their own theme and background even when visiting your profile. Signed-out pages use
System mode. Startup automatically applies `V13__member_customization.sql`; no storage bucket or
server image directory is required. Database backups now include uploaded images.

Uploads accept JPEG and PNG files up to 5 MB, with each side no larger than 4096 pixels and at most
16 million pixels total. Profile photos are centre-cropped to 256 × 256; backgrounds fit within
1920 × 1920 without upscaling. JPEG camera orientation is applied before resizing. Images are
re-encoded as JPEGs without their original metadata; transparency is flattened onto white.
Only one image is processed at a time per server instance; busy uploads can be retried.

Uploading requires JavaScript. Theme/profile editing and image removal use normal forms. Background
images require an authenticated owner session, while profile photos are public wherever the global
`MEMBERS_ONLY` policy permits access. These controls are website-only; existing mobile APIs are unchanged.

### Notifications

Signed-in pages show a bell in the header with an unread badge and a dropdown of the 10 most
recent notices; **View all notifications** opens the paginated history at `/notifications`.
Notices are created for: follow requests, accepted follow requests, new posts (only from members
you follow), likes on your posts, and comments or replies on your posts. Members never notify
themselves.

- Clicking a notice marks that one read and goes to its target (requests page, profile, post or
  comment); **Mark all as read** marks the currently visible notices read. Opening the dropdown or loading a page never marks
  anything read.
- Withdrawn actions (cancelled request, removed like, deleted comment) and content you can no
  longer see are excluded from the list and count on the next page load or poll.
- Notices start fresh: nothing is back-filled for activity that happened before the feature.
- The dropdown is server-rendered, so it works without JavaScript (each notice is a form button
  and the history page has plain "older" links). With JavaScript, a visible page refreshes it from
  `GET /notifications/recent` every 30 seconds; hidden tabs do not poll and refresh on return.
- `POST /notifications/{id}/open` and `POST /notifications/read-all` use the session cookie and its
  CSRF token. History and polling responses are user-specific and `no-store`. `/notifications/recent` is a
  web-session endpoint for the page script, not part of the mobile API.

### Post fonts and media attachments

In **Create post** choose a whole-post **Font** (sans-serif, serif, monospace) and **Text size**
(small, normal, large); the editor previews them and readers see them in the feed, profiles and
conversation. Posts can include up to **4 attachments**, and comments and replies can each include
**1 attachment**. Text is optional when an attachment is present; a submission with neither text nor
media is rejected. Likes/reactions are unchanged. Profile photos and backgrounds retain the separate
JPEG/PNG processing rules above.

| Media | Accepted file extensions | Maximum per file |
|---|---|---|
| Images and animations | JPEG, JPG, PNG, WebP, GIF, HEIC, AVIF, APNG | 20 MB |
| Video | MP4, WebM, MOV, M4V | 100 MB |
| Audio | MP3, AAC, M4A, WAV, OGG, FLAC | 25 MB |

Attachments are stored as **original files** in PostgreSQL: there is no transcoding, background
conversion worker, or audio/video duration cap. FFmpeg and ImageMagick are not required. Original
GIF, animated WebP and APNG files retain their animation, and images retain transparency. **Embedded
metadata is also retained**, including any camera/location metadata; remove sensitive metadata before
uploading. File extensions and signatures are checked, but this is not a full decoder or malware scan.

Images use the browser's image display, and audio/video use native playback controls without autoplay.
Inline display/playback depends on the viewer's browser and the codecs inside the file; accepting a
format does not guarantee playback. A download link is available for every attachment, including
formats the browser cannot display. Media responses support single HTTP byte ranges for seeking.
Attachments and downloads follow the post's audience, including media on comments and replies.
Uploads are buffered and serialized per server instance; allow heap headroom for four maximum-sized
videos plus request/JDBC buffers. Original files also increase database and backup storage usage.

The website shows attachment previews and removal controls before publishing. Media publishing
requires JavaScript and sends a multipart upload with the `X-CSRF-Token` header; without JavaScript,
normal text forms still work. Upload reads have a 60-second deadline, with the server's socket timeout
bounding blocked reads. Oversized files (413), timed-out uploads (408), busy uploads (429) and expired
sessions (403) show an error so the composer can be retried. The JSON API also supports multipart
attachments while preserving existing text-only JSON requests. Existing post images remain readable.

#### API attachment uploads

Use the authenticated actor's bearer token and `multipart/form-data` with file parts named
`attachments` (repeat that field for multiple post attachments):

| Action | Endpoint | Text/style fields |
|---|---|---|
| Publish a post | `POST /posts` | `body`, `visibility`, `font`, `size` |
| Add a comment | `POST /posts/{id}/comments` | `body` |
| Reply to a comment | `POST /api/posts/{id}/comments/{comment}/replies` | `body` |

For example, with a bearer token from the existing login API:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -F 'body=Listen to this' \
  -F 'visibility=members' \
  -F 'attachments=@/path/to/recording.m4a' \
  http://localhost:8080/posts
```

Responses and feed/comment listings include an `attachments` array containing `mediaType`, `url`,
and `downloadUrl`. Fetch the returned URL with the same authentication when required by the post's
audience. `downloadUrl` forces a download with a generated safe filename; uploaded filenames are not
used in response headers. Existing text-only JSON requests keep working. Cookie-authenticated writes
also require the existing CSRF token.

### Personal news and article feeds

Open **News & Articles** next to **All posts** and **Following**, then expand **Manage feeds**.
Add a name (for example, News or AI articles) and the publisher's public RSS or Atom URL.
You can subscribe to up to 20 sources. Use a feed URL, not a normal website or article URL;
this feature does not scrape websites or bypass paywalls.

All your sources appear together in one newest-first list with source names, plain-text abstracts,
original article links, and **Older articles** pagination. Subscriptions are personal: other members
cannot see or remove your sources. Removing a source also removes its saved articles from your list.
The social-post tabs are unchanged.

Sources and articles are saved in PostgreSQL. Opening the article tab (or requesting the article API)
refreshes sources that have not been attempted in the last 15 minutes. A slow source can delay that
visit. To avoid waiting for every slow source, no more refreshes are started once a visit has spent
10 seconds refreshing; the current request finishes and remaining sources are tried on later visits.
Each source has a shared network timeout across redirects. Failed sources show a notice and keep their previously saved articles; another visit after
15 minutes retries them. No background worker or AI credentials are needed. Abstracts come from
feed content, not AI-generated summaries. RSS `content:encoded` (used by some Medium feeds) and Atom
content are supported when there is no summary. Excerpts are capped at 600 characters and titles at
200. Undated articles use their first import time for ordering; imported article dates remain stable
across refreshes so saved pagination links do not skip or repeat existing articles.

Feed fetching follows at most three redirects, rejects private/internal network destinations, disables
XML external entities, and limits response size. All feed text is rendered without publisher HTML.

Authenticated JSON endpoints (use a bearer token, or a session cookie with CSRF protection for writes):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/me/feeds` | List your sources and their refresh status |
| `POST` | `/api/me/feeds` | Add a source using `{"name":"AI articles","url":"https://publisher.example/feed.xml"}` |
| `DELETE` | `/api/me/feeds/SOURCE_UUID` | Remove your source and its articles |
| `GET` | `/api/me/articles?limit=20` | Combined articles, plus a `next` cursor or `null` |

For subsequent article pages, URL-encode the returned `next` value as `before`. Limits range from
1 to 100. Article objects contain `id`, `sourceId`, `sourceName`, `title`, `url`, `abstract`, and
`publishedAt`; source objects include `lastAttemptAt`, `lastSuccessAt`, and `lastError`.
Startup applies migration `V12__article_feeds.sql` automatically.

### Post audiences

Create post defaults to **Members only** (your current approved followers and you).
Choose **Public** for everyone allowed by the global `MEMBERS_ONLY` setting, or **Private**
for yourself only. Existing posts remain Public. Use **Change audience** on your own post to
update it after publishing. Audience rules apply to feeds, profiles, hashtags, direct post links,
comments, replies, and likes. Direct access outside the audience returns `404`.

Newly approved followers can read older Members-only posts. Unfollowing or removing a follower
revokes that access on subsequent requests. Public posts remain readable. Counts refresh on page load.

JSON publishing accepts `visibility` (`public`, `members`, or `private`, default `members`).
`PUT /posts/POST_ID/visibility` with `{"visibility":"private"}` changes the audience for the
signed-in author and returns `204`. Cookie writes require CSRF protection. Post/feed responses
include `visibility`.

Password-reset emails also appear in the development inbox. Existing authors remain read-only legacy profiles; new accounts cannot claim their UUIDs.

In another terminal, check that the API responds:

```bash
curl -i http://localhost:8080/health
```

Expected response: HTTP `200` with `{"status":"ok"}`. See the API examples below to create and query posts.

### Languages and automatic translation

The web interface supports English, Marathi (मराठी), Hindi (हिन्दी), Gujarati (ગુજરાતી),
Tamil (தமிழ்), and Malayalam (മലയാളം). Use the language selector at the top of any page.
Signed-out visitors start in English; their selection is remembered in that browser for a year.
For signed-in members, changing the language saves it to their account and applies across devices.
Existing accounts default to English. Account emails remain in English.

Posts and comments are automatically translated into the selected language using Google Cloud
Translation Basic (v2). Original content is preserved; **Show original** expands the source text.
Hashtags and member names are preserved. The JSON API continues to return original content.

To enable content translation:

1. Enable the **Cloud Translation API** in a Google Cloud project with billing enabled.
2. Create a server-side API key restricted to the Cloud Translation API.
3. Export `GOOGLE_TRANSLATION_API_KEY` before starting the server. This is separate from Google sign-in credentials.

```bash
export GOOGLE_TRANSLATION_API_KEY="your-key"
./gradlew :server:run
```

Successful translations are cached in server memory for up to 24 hours (2,000 entries), keyed by
original text and target language. The cache resets on restart. Uncached post/comment text is sent
to Google, including when the selected language is English; Google detects the source language.
Requests are batched with a three-second timeout per batch. Missing credentials or service failures
leave the original content readable with a translation-unavailable notice. Interface translations
work without Google credentials. Automated tests use a local fake endpoint; live translation
requires your configured Google Cloud project.

### Configuration

The application reads environment variables, with these defaults:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/social
DATABASE_USER=social
DATABASE_PASSWORD=social
PORT=8080
BASE_URL=http://localhost:8080
MEMBERS_ONLY=false
COOKIE_SECURE=false
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_FROM=openly@localhost
SMTP_STARTTLS=false
```

These defaults match `docker-compose.yml`, so no configuration is needed for the local setup above. `.env.example` documents the variables; the application does not automatically load it.

To use a different web/API port:

```bash
PORT=8081 BASE_URL=http://localhost:8081 ./gradlew :server:run
```

To connect to a different database, export `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` before starting the API. The configured database must already exist.

Set `MEMBERS_ONLY=true` to require sign-in for feed, profile, post, and comment reading. Registration, login, recovery, Google callbacks, and health remain reachable. Writes always require a session.

For HTTPS deployments, set `BASE_URL` to the public origin and `COOKIE_SECURE=true`. Configure `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, optional `SMTP_USER`/`SMTP_PASSWORD`, and `SMTP_STARTTLS=true` for your mail provider.

### Google sign-in

Create a Google OAuth **Web application** client and register this authorized redirect URI for local development:

```text
http://localhost:8080/auth/google/callback
```

Setup steps:

1. Select or create a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Configure the app name, contact email, and audience in Google Auth Platform. Add your account as a test user if required by the selected audience/testing configuration.
3. In [Clients](https://console.cloud.google.com/auth/clients), create a **Web application** OAuth client and register the redirect URI above.
4. Save the client ID and client secret privately. In the terminal used to start the application, export both values.

For macOS's default zsh shell:

```bash
export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
read -s "GOOGLE_CLIENT_SECRET?Google client secret: "
export GOOGLE_CLIENT_SECRET
BASE_URL=http://localhost:8080 ./gradlew :server:run
```

If using PostgreSQL on port `5433`, add `DATABASE_URL=jdbc:postgresql://localhost:5433/social` to the startup command. Stop the existing server before restarting with the credentials.

Use **Continue with Google** on the login page. To attach Google to an existing email/password account, sign in first and choose **Link Google account** on your profile. Accounts are never merged solely because email addresses match.

Once linked, **Connected accounts** on your own profile shows the linked Google email addresses
instead of the link button. These addresses are refreshed on Google sign-in and are visible only
to you. Older links show **Google account connected** until the next Google sign-in records their email.

The callback URL must exactly match Google's registered URI. Use `localhost` consistently in your browser. If changing `PORT` or deploying to another origin, update both `BASE_URL` and the registered redirect URI. Keep client secrets out of source control.

Without Google credentials, email/password sign-in still works; the Google route reports that it is not configured. Live Google sign-in requires your credentials and provider configuration; automated tests cover local protocol and linking checks.

### Stop locally

Press `Ctrl+C` in the server terminal, then stop PostgreSQL and the development inbox:

```bash
docker compose down
```

Database data is retained in the named Docker volume for the next startup.

### Troubleshooting

- **Database connection refused:** check that Docker is running and `docker compose ps` shows PostgreSQL as healthy. The default database port is `5432`.
- **Port already in use:** stop the process using port `8080`, or start the API with a different `PORT` as shown above. PostgreSQL also requires host port `5432` to be available with the supplied Compose configuration.
- **Java/Gradle compatibility error:** check `java -version` and select JDK 21 before running `./gradlew` again.
- **Dependency download failure:** the first build needs access to the Gradle distribution service and Maven Central.
- **Task `:api:run` or `:web:run` not found:** run `./gradlew :server:run`; the server assembles both modules.
- **Google sign-in is not configured:** export `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in the startup terminal and restart the server. `.env.example` is not loaded automatically.
- **Google `redirect_uri_mismatch`:** check that the registered redirect URI exactly matches `BASE_URL` followed by `/auth/google/callback`.

If another database already uses port `5432`, run this project's database on `5433`:

```bash
POSTGRES_PORT=5433 docker compose up -d postgres mailpit
DATABASE_URL=jdbc:postgresql://localhost:5433/social ./gradlew :server:run
```

## API

### Comment replies and likes

On a conversation page, signed-in members can **Reply**, **Like**, or **Unlike** any comment or
reply. Replies can nest at any depth; visual indentation stops growing after three levels on
small screens. **View replies** expands 20 direct replies at a time, with **More replies** for
the next page. Without JavaScript, these links open a paginated thread page. Replies use the
same 1–1,000 character limit as comments. The post's comment total includes all nested replies.
Existing comments remain top-level comments.

JSON endpoints (replace `POST_ID` and `COMMENT_ID`):

- `GET /api/posts/POST_ID/comments` — top-level comments, including `parentId`, `likeCount`,
  `likedByViewer`, and direct `replyCount`.
- `GET /api/posts/POST_ID/comments/COMMENT_ID/replies` — direct replies with the same fields.
  Both list endpoints accept `limit=1..100` (default 20) and return `next`; pass it unchanged,
  URL-encoded, as `after`.
- `POST /api/posts/POST_ID/comments/COMMENT_ID/replies` with `{"body":"Your reply"}` — create
  a reply, returning `201` and the comment. Authorship comes from the session.
- `POST /api/posts/POST_ID/comments/COMMENT_ID/likes` — like, returning `204`.
- `DELETE /api/posts/POST_ID/comments/COMMENT_ID/likes` — unlike, returning `204`.

Writes require sign-in; cookie-based writes also require CSRF protection. Each member can like
a comment once, and repeated like/unlike requests are harmless. A missing comment or a comment
belonging to a different post returns `404`. Reads retain the existing `MEMBERS_ONLY` setting.

The web frontend uses HttpOnly session cookies with CSRF-protected writes. Mobile/API clients can sign in using a verified email/password account and use the returned opaque bearer token (expires after seven days). Store tokens securely on the client. The server derives authorship from the session; callers cannot choose another author's ID.

Sign in using an account registered and verified through the web flow:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'content-type: application/json' \
  -d '{"email":"alice@example.com","password":"your-password"}'
```

Set `TOKEN` to the response's `token` value for the examples below.

Create a post:

```bash
curl -i -X POST http://localhost:8080/posts \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"body":"Hello world #Kotlin #DDD"}'
```

List the latest posts:

```bash
curl 'http://localhost:8080/api/feed?limit=20'
```

Get a post (replace `POST_ID`):

```bash
curl http://localhost:8080/posts/POST_ID
```

Like a post:

```bash
curl -X POST http://localhost:8080/posts/POST_ID/likes \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{}'
```

Unlike:

```bash
curl -X DELETE http://localhost:8080/posts/POST_ID/likes \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{}'
```

Comment:

```bash
curl -X POST http://localhost:8080/posts/POST_ID/comments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"body":"Great post!"}'
```

Search hashtag:

```bash
curl 'http://localhost:8080/api/feed?tag=kotlin&limit=20'
```

`/api/feed` returns post summaries, reaction/comment counts, viewer reaction state, and a `next` cursor. Pass that cursor unchanged using URL encoding as `before` to retrieve older posts. It includes both timestamp and ID so tied timestamps do not skip posts.

Additional endpoints:

- `GET /api/feed?scope=following` — authenticated Following feed, including your own posts; supports `limit`, `before`, `tag`, and `author`. Default scope is `all`.
- `PUT /api/me/following/USER_ID` — request to follow; returns `followState` (`pending` or `accepted`). Repeating a request preserves its current state.
- `DELETE /api/me/following/USER_ID` — cancel a pending request or unfollow.
- `GET /api/me/follow-requests` — incoming pending requests.
- `POST /api/me/follow-requests/USER_ID/accept` — approve that member's incoming request.
- `DELETE /api/me/follow-requests/USER_ID` — reject a pending request.
- `GET /api/me/followers` — approved followers.
- `DELETE /api/me/followers/USER_ID` — remove an approved follower.
- `GET /api/me/following` — approved followed members.

All `/api/me/` routes require authentication. Follow lists return `members` (public ID/display name),
`next`, and `pendingCount`; use `limit=1..100` (default 20) and pass `next` unchanged as `after`.
Relationship changes other than PUT return `204`. Accept/reject/remove operations affect only
the authenticated recipient's relationships. Missing relationships are harmless no-ops.
`GET /api/profiles/USER_ID` also returns the viewer's outgoing `followState` (`none`, `pending`, or `accepted`).

- `GET /api/feed?author=USER_ID` — posts for a profile.
- `GET /api/profiles/USER_ID` — public ID/display name, never email or password hash.
- `GET /api/posts/POST_ID/comments?limit=20` — paginated comments; pass `next` as `after` for the next page.
- `POST /api/auth/logout` with the bearer token — revoke that session.

Add the Authorization header for read requests when `MEMBERS_ONLY=true`. The older `/posts` and `/hashtags/{tag}/posts` read endpoints remain available for compatibility; use `/api/feed` for stable summary pagination.

Health check:

```bash
curl http://localhost:8080/health
```

## DDD boundaries

- **Domain** (`:domain`): `Post`, `Comment`, `Hashtag`, IDs and invariant enforcement. No database or HTTP dependency.
- **Application** (`:application`): `PostService` use cases and `PostRepository` port.
- **Infrastructure** (`:infrastructure`): PostgreSQL adapter, connection pool and Flyway migration.
- **HTTP common** (`:http-common`): shared session resolution, CSRF checks, authentication rate limiting, form parsing, and feed cursor encoding.
- **API** (`:api`): JSON post, feed, comment, profile, and bearer-authentication endpoints, with HTTP contract tests.
- **Notifications** (`:notifications`): notification kinds, the store port, and the list, unread-count and mark-read service. PostgreSQL storage lives in infrastructure.
- **Web** (`:web`): server-rendered pages, forms, HTML/CSS, browser account flows, and Google callbacks, with handler and browser tests.
- **Server** (`:server`): executable entry point, environment configuration, dependency wiring, and combined routing/access-control tests.

The `web` and `api` modules do not depend on each other. Both use `application`, `domain`, and `http-common`; currently they also use infrastructure's secure-token generator. `infrastructure` implements application ports. The `server` module assembles both HTTP adapters into one process on one port, sharing services, sessions, and PostgreSQL. Existing URLs and the Google callback URI are unchanged.

```text
server
├── web         HTML pages, browser login, Google callback
├── api         JSON endpoints and mobile bearer login
├── http-common Shared HTTP security and cursor utilities
├── application Use cases and ports
├── domain      Business rules
└── infrastructure PostgreSQL, SMTP, Google, password hashing
```

Start both frontends with `./gradlew :server:run`; `:web` and `:api` are library modules, not separate servers.

## Persistence model

`posts`, `hashtags`, `post_hashtags`, `post_likes`, `comments`, `accounts`, `account_tokens`, `sessions`, `google_identities`, `oauth_attempts`, and `member_follows`. Composite primary keys make hashtag links, likes, and directed follow relationships unique. Follow relationships have pending/accepted states, prohibit self-following, and cascade with account deletion. Reactions and comments use targeted writes. Web feeds load summaries and counts; comment lists have separate cursors. Compatibility endpoints can still load full aggregates.

## Tests

```bash
./gradlew test
```

Domain/application tests run without PostgreSQL. Integration tests create isolated PostgreSQL 17 containers and apply all migrations. HTTP tests cover session and CSRF checks, while Playwright exercises desktop/mobile reading and registration → verification → login → publish → comment → like → profile editing. Browser journeys use in-memory adapters; database behavior is verified separately.

Each module owns its tests under `src/test/kotlin`. Run suites independently with:

| Module | Test coverage |
|---|---|
| `domain` | Post/comment validation, hashtags, likes, and immutable state |
| `application` | Publishing, account verification/recovery, profiles, and Google linking rules |
| `infrastructure` | Real PostgreSQL persistence, migrations, cursors, targeted writes, rollback, sessions, password hashing, and Google adapter checks |
| `http-common` | Session access, CSRF enforcement, rate limits, and cursor encoding |
| `api` | JSON feed/profile contracts, publishing identity, bearer login/logout, and authentication failures |
| `web` | Page/form handlers, Google callback-state checks, desktop/mobile browsing, and signed-in browser journeys |
| `server` | Combined routing, health, login availability, and public/members-only access |

```bash
./gradlew :domain:test :application:test
./gradlew :http-common:test
./gradlew :api:test
./gradlew :web:test
./gradlew :server:test
./gradlew :infrastructure:test
```

`:web:test` needs a Playwright browser; `:infrastructure:test` needs Docker. See the setup above for OrbStack and installed Chrome. `./gradlew test` runs all module suites; `./gradlew build` builds and checks every module and packages the server distribution.

## Before production

1. Configure HTTPS, secure cookies, real SMTP, database credentials, and Google OAuth credentials/redirect URIs.
2. Review request-size limits, spam/moderation controls, structured logging, monitoring, and proxy configuration. The authentication limiter is process-local and keyed by the direct peer address; deployments behind proxies or multiple replicas need a suitable shared/edge limit.
3. Add periodic cleanup of expired sessions, account tokens, and OAuth attempts, plus operational health/readiness checks and backup procedures.
4. Exercise the real Google provider and SMTP delivery in your deployment environment, and perform a manual accessibility/visual review.
5. Review account lifecycle races and recovery behavior for your deployment. PostgreSQL verification/reset consumes tokens transactionally; password resets also revoke sessions and other reset links.
6. Define edit/delete authorization when those features are added. Consider an outbox only when reliable asynchronous events are required.
