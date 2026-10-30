package com.example.social.web

import com.example.social.application.*
import com.example.social.http.*
import org.http4k.core.*
import com.example.social.domain.UserId
import org.http4k.routing.*

fun settingsRoutes(customization: CustomizationService, accounts: Accounts, accountService: AccountService, access: AccessControl): RoutingHttpHandler {
    fun invalid(error: IllegalArgumentException) = htmlPage("Invalid settings",
        "<section class=\"card\"><h1>Check your settings</h1><p>${escapeHtml(error.message ?: "Invalid input")}</p><a href=\"/settings\">Return to settings</a></section>",
        Status.BAD_REQUEST)
    fun saved() = Response(Status.SEE_OTHER).header("Location", "/settings").header("Cache-Control", "no-store")
    fun write(action: (Session, Map<String, String>) -> Unit): HttpHandler = access.protect { request ->
        val session = access.session(request)!!
        try { action(session, formFields(request)); saved() } catch (error: IllegalArgumentException) { invalid(error) }
    }
    fun imageKind(request: Request) = when (request.path("kind")) {
        "avatar" -> MemberImageKind.AVATAR
        "background" -> MemberImageKind.BACKGROUND
        "header" -> MemberImageKind.HEADER
        else -> null
    }
    fun media(image: MemberImage?) = if (image == null) Response(Status.NOT_FOUND)
        else Response(Status.OK).header("Content-Type", "image/jpeg").header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, no-store").body(Body(java.nio.ByteBuffer.wrap(image.bytes)))
    return routes(
        "/media/profiles/{id}" bind Method.GET to { request ->
            val owner = try { UserId.parse(request.path("id").orEmpty()) } catch (_: IllegalArgumentException) { null }
            media(owner?.let { customization.image(it, MemberImageKind.AVATAR) })
        },
        "/media/background" bind Method.GET to { request ->
            val session = access.session(request)
            if (session == null) Response(Status.UNAUTHORIZED).body("Sign in to continue")
            else media(customization.image(session.accountId, MemberImageKind.BACKGROUND))
        },
        "/media/header" bind Method.GET to { request ->
            val session = access.session(request)
            if (session == null) Response(Status.UNAUTHORIZED).body("Sign in to continue")
            else media(customization.image(session.accountId, MemberImageKind.HEADER))
        },
        "/settings/images/{kind}" bind Method.POST to access.protect { request ->
            val kind = imageKind(request)
            val session = access.session(request)!!
            if (kind == null) Response(Status.NOT_FOUND)
            else try {
                val max = CustomizationService.MAX_UPLOAD_BYTES
                if ((request.header("Content-Length")?.toLongOrNull() ?: 0L) > max) throw ImageTooLarge()
                customization.uploadImage(session.accountId, kind, request.body.stream.readNBytes(max + 1))
                saved()
            } catch (error: ImageTooLarge) { invalid(error).status(Status.REQUEST_ENTITY_TOO_LARGE) }
            catch (_: ImageProcessingBusy) { Response(Status.TOO_MANY_REQUESTS).header("Retry-After", "1").body("Image processing is busy. Please try again.") }
            catch (error: IllegalArgumentException) { invalid(error) }
        },
        "/settings/images/{kind}/remove" bind Method.POST to access.protect { request ->
            val kind = imageKind(request)
            if (kind == null) Response(Status.NOT_FOUND)
            else { customization.removeImage(access.session(request)!!.accountId, kind); saved() }
        },
        "/settings" bind Method.GET to access.protect { request ->
            val session = access.session(request)
            val account = session?.let { accounts.byId(it.accountId) }
            if (session == null || account == null) Response(Status.UNAUTHORIZED).body("Sign in to continue")
            else {
                val preferences = customization.preferences(session.accountId)
                val csrf = "<input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">"
                val themes = ThemeMode.entries.joinToString("") {
                    val value = it.name.lowercase()
                    "<option value=\"$value\"${if (it == preferences.theme) " selected" else ""}>${value.replaceFirstChar(Char::uppercase)}</option>"
                }
                htmlPage("Settings", """
                    <section class="intro"><span class="eyebrow">YOUR SPACE</span><h1>Settings</h1></section>
                    <section class="card"><form method="post" action="/settings/appearance">$csrf<label for="theme">Theme</label><select id="theme" name="theme">$themes</select><p><button class="button" type="submit">Save appearance</button></p></form></section>
                    <section class="card"><form method="post" action="/settings/profile">$csrf<label for="displayName">Display name</label><input id="displayName" name="displayName" maxlength="80" required value="${escapeHtml(account.displayName)}"><label for="bio">Bio</label><textarea id="bio" name="bio" translate="no" maxlength="${CustomizationService.MAX_BIO}">${escapeHtml(preferences.bio)}</textarea><p><button class="button" type="submit">Save profile</button></p></form></section>
                    ${imageControls(MemberImageKind.AVATAR, preferences.avatarVersion, session.accountId, csrf)}
                    ${imageControls(MemberImageKind.HEADER, preferences.headerVersion, session.accountId, csrf)}
                    ${imageControls(MemberImageKind.BACKGROUND, preferences.backgroundVersion, session.accountId, csrf)}
                    $imageUploadScript
                """.trimIndent()).header("Cache-Control", "no-store")
            }
        },
        "/settings/appearance" bind Method.POST to write { session, fields ->
            customization.saveTheme(session.accountId, fields["theme"])
        },
        "/settings/profile" bind Method.POST to write { session, fields ->
            val name = fields["displayName"].orEmpty().trim()
            require(name.isNotBlank() && name.length <= 80) { "Display name must contain 1–80 characters" }
            val bio = customization.validBio(fields["bio"])
            accountService.rename(session.accountId, name)
            customization.saveBio(session.accountId, bio)
        }
    )
}

private fun imageControls(kind: MemberImageKind, version: java.util.UUID?, owner: UserId, csrf: String): String {
    val name = kind.name.lowercase()
    val avatar = kind == MemberImageKind.AVATAR
    val header = kind == MemberImageKind.HEADER
    val label = if (avatar) "Profile photo" else if (header) "Header background" else "Body background"
    val preview = if (version == null) "" else {
        val source = if (avatar) "/media/profiles/${owner.value}?v=$version" else "/media/$name?v=$version"
        "<img id=\"$name-preview\" class=\"image-preview${if (avatar) " avatar-preview" else ""}\" src=\"$source\" alt=\"$label preview\">"
    }
    val remove = if (version == null) "" else "<form action=\"/settings/images/$name/remove\" method=\"post\">$csrf<button class=\"button\" type=\"submit\">${if (avatar) "Remove profile photo" else if (header) "Remove header image" else "Remove background"}</button></form>"
    return """
        <section class="card"><h2>$label</h2><p>${if (avatar) "Your photo is public and appears beside your posts and on your profile." else if (header) "Only you see this header image. It appears in the top navigation across the website." else "Only you see this background. It follows your account across devices."}</p>
        $preview<form class="image-upload" action="/settings/images/$name" method="post">$csrf
        <label for="$name-file">$label</label><input id="$name-file" name="image" type="file" accept="image/jpeg,image/png" required aria-describedby="$name-help">
        <p id="$name-help">JPEG or PNG, up to 5 MB and 4096 × 4096 pixels (16 megapixels maximum). Images are resized and metadata is removed.</p>
        <button id="upload-$name" class="button" type="submit">${if (version == null) "Upload image" else "Replace image"}</button><p class="upload-message" role="status" aria-live="polite"></p></form>$remove
        <noscript><p>JavaScript is required to upload images. You can still change your theme, edit your profile, and remove images.</p></noscript></section>
    """.trimIndent()
}

private val imageUploadScript = """
    <script>
    document.querySelectorAll('.image-upload').forEach(form => form.addEventListener('submit', async event => {
      event.preventDefault();
      const file = form.querySelector('input[type=file]').files[0];
      const message = form.querySelector('.upload-message');
      const button = form.querySelector('button[type=submit]');
      if (!file) return;
      if (file.size > ${CustomizationService.MAX_UPLOAD_BYTES}) { message.textContent = 'Images must be 5 MB or smaller'; return; }
      button.disabled = true;
      message.textContent = 'Uploading image…';
      try {
        const response = await fetch(form.action, {method: 'POST', credentials: 'same-origin', headers: {
          'X-CSRF-Token': form.elements.csrf.value, 'Content-Type': file.type || 'application/octet-stream'
        }, body: file});
        if (response.ok) window.location.assign('/settings');
        else message.textContent = response.status === 429 ? 'Image processing is busy. Please try again.' : response.status === 413 ? 'Images must be 5 MB or smaller' : 'Upload failed. Choose a valid JPEG or PNG within the limits, or reload the page and sign in again.';
      } catch (_) { message.textContent = 'Upload failed. Check your connection and try again.'; }
      finally { button.disabled = false; }
    }));
    </script>
""".trimIndent()
