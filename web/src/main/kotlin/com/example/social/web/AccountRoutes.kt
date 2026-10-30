package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.infrastructure.secureToken
import org.http4k.core.*
import org.http4k.routing.*
import java.time.Instant

// Copyright (c) 2016 Google Inc. All rights reserved.
// Original artwork: https://www.gstatic.com/firebasejs/ui/2.0.0/images/auth/google.svg
private const val googleLogo = """
<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 118 120" fill="none" fill-rule="evenodd" aria-hidden="true" focusable="false" style="flex-shrink:0;margin-right:.5rem;vertical-align:middle">
<path fill="#4285F4" d="M117.6,61.3636364 C117.6,57.1090909 117.218182,53.0181818 116.509091,49.0909091 L60,49.0909091 L60,72.3 L92.2909091,72.3 C90.9,79.8 86.6727273,86.1545455 80.3181818,90.4090909 L80.3181818,105.463636 L99.7090909,105.463636 C111.054545,95.0181818 117.6,79.6363636 117.6,61.3636364 Z"/>
<path fill="#34A853" d="M60,120 C76.2,120 89.7818182,114.627273 99.7090909,105.463636 L80.3181818,90.4090909 C74.9454545,94.0090909 68.0727273,96.1363636 60,96.1363636 C44.3727273,96.1363636 31.1454545,85.5818182 26.4272727,71.4 L6.38181818,71.4 L6.38181818,86.9454545 C16.2545455,106.554545 36.5454545,120 60,120 Z"/>
<path fill="#FBBC05" d="M26.4272727,71.4 C25.2272727,67.8 24.5454545,63.9545455 24.5454545,60 C24.5454545,56.0454545 25.2272727,52.2 26.4272727,48.6 L26.4272727,33.0545455 L6.38181818,33.0545455 C2.31818182,41.1545455 0,50.3181818 0,60 C0,69.6818182 2.31818182,78.8454545 6.38181818,86.9454545 L26.4272727,71.4 Z"/>
<path fill="#EA4335" d="M60,23.8636364 C68.8090909,23.8636364 76.7181818,26.8909091 82.9363636,32.8363636 L100.145455,15.6272727 C89.7545455,5.94545455 76.1727273,0 60,0 C36.5454545,0 16.2545455,13.4454545 6.38181818,33.0545455 L26.4272727,48.6 C31.1454545,34.4181818 44.3727273,23.8636364 60,23.8636364 Z"/>
</svg>
"""

fun accountRoutes(service: AccountService, accounts: Accounts, sessions: Sessions, access: AccessControl, secureCookies: Boolean): RoutingHttpHandler {
    fun sessionCookie(token: String, maxAge: Int = 604800) = "session=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=$maxAge" + if (secureCookies) "; Secure" else ""
    fun page(title: String, action: String, fields: String, submit: String, token: String? = null): Response {
        val csrf = secureToken()
        val hidden = token?.let { "<input type=\"hidden\" name=\"token\" value=\"${escapeHtml(it)}\">" } ?: ""
        return htmlPage(title, """
            <section class="intro"><span class="eyebrow">WELCOME TO ONELY</span><h1>${escapeHtml(title)}</h1></section>
            <section class="card"><form method="post" action="$action"><input type="hidden" name="csrf" value="$csrf">$hidden$fields<p><button class="button" type="submit">$submit</button></p></form>
            <p><a href="/login">Sign in</a> · <a href="/register">Create account</a> · <a href="/forgot-password">Forgot password?</a></p></section>
        """.trimIndent()).header("Set-Cookie", "form_csrf=$csrf; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600" + if (secureCookies) "; Secure" else "")
            .header("Cache-Control", "no-store")
    }
    fun guarded(action: (Request, Map<String, String>) -> Response): HttpHandler = { request ->
        val fields = formFields(request)
        if (cookie(request, "form_csrf").isNullOrBlank() || !constantEquals(cookie(request, "form_csrf")!!, fields["csrf"].orEmpty()))
            Response(Status.FORBIDDEN).body("Invalid form token. Reload the page.")
        else try { action(request, fields) }
        catch (_: AuthenticationFailed) { htmlPage("Unable to sign in", "<section class=\"card\"><h1>Unable to complete request</h1><p>Check your credentials, verify your email, or request a new link.</p><a href=\"/login\">Try again</a></section>", Status.UNAUTHORIZED) }
        catch (error: IllegalArgumentException) { htmlPage("Invalid input", "<section class=\"card\"><h1>Check your details</h1><p>${escapeHtml(error.message ?: "Invalid input")}</p><a href=\"/register\">Return to registration</a></section>", Status.BAD_REQUEST) }
    }
    val email = "<label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" autocomplete=\"email\" maxlength=\"254\" required>"
    val password = "<label for=\"password\">Password</label><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" maxlength=\"128\" required>"
    val newPassword = "<label for=\"password\">New password</label><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"new-password\" minlength=\"12\" maxlength=\"128\" required><p>Use 12–128 characters.</p>"
    return routes(
        "/login" bind Method.GET to { page("Welcome back", "/login", "<p><a class=\"button\" href=\"/auth/google\">$googleLogo<span>Continue with Google</span></a></p>" + email + password, "Sign in") },
        "/register" bind Method.GET to { page("Join the conversation", "/register", "<label for=\"displayName\">Display name</label><input id=\"displayName\" name=\"displayName\" autocomplete=\"nickname\" maxlength=\"80\" required>" + email + newPassword, "Create account") },
        "/forgot-password" bind Method.GET to { page("Reset your password", "/forgot-password", email, "Send reset link") },
        "/reset-password" bind Method.GET to { request -> page("Choose a new password", "/reset-password", newPassword, "Save password", request.query("token").orEmpty()) },
        "/verify" bind Method.GET to { request -> page("Verify your email", "/verify", "<p>Confirm your email to start participating.</p>", "Verify email", request.query("token").orEmpty()) },
        "/login" bind Method.POST to guarded { request, fields ->
            val account = service.login(fields["email"].orEmpty(), fields["password"].orEmpty())
            access.session(request)?.let { sessions.delete(it.digest) }
            val token = secureToken()
            sessions.create(Session(tokenDigest(token), account.id, secureToken(), Instant.now().plusSeconds(604800)))
            Response(Status.SEE_OTHER).header("Location", "/").header("Set-Cookie", sessionCookie(token)).header("Cache-Control", "no-store")
        },
        "/register" bind Method.POST to guarded { _, fields ->
            service.register(fields["email"].orEmpty(), fields["password"].orEmpty(), fields["displayName"].orEmpty())
            htmlPage("Check your email", "<section class=\"card\"><h1>Check your email</h1><p>If registration is available for this address, a verification link has been sent.</p><a href=\"/login\">Return to sign in</a></section>")
        },
        "/verify" bind Method.POST to guarded { _, fields ->
            service.verify(fields["token"].orEmpty())
            htmlPage("Email verified", "<section class=\"card\"><h1>Email verified</h1><p>You can now sign in.</p><a class=\"button\" href=\"/login\">Sign in</a></section>")
        },
        "/forgot-password" bind Method.POST to guarded { _, fields ->
            service.requestReset(fields["email"].orEmpty())
            htmlPage("Check your email", "<section class=\"card\"><h1>Check your email</h1><p>If a verified account exists, a password-reset link has been sent.</p></section>")
        },
        "/reset-password" bind Method.POST to guarded { _, fields ->
            service.resetPassword(fields["token"].orEmpty(), fields["password"].orEmpty())
            htmlPage("Password updated", "<section class=\"card\"><h1>Password updated</h1><a href=\"/login\">Sign in with your new password</a></section>")
        },
        "/logout" bind Method.POST to access.protect { request ->
            access.session(request)?.let { sessions.delete(it.digest) }
            Response(Status.SEE_OTHER).header("Location", "/login").header("Set-Cookie", sessionCookie("", 0))
        }
    )
}
