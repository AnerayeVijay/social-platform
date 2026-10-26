package com.example.social.infrastructure

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties

class SmtpLinks(
    private val baseUrl: String,
    host: String = "localhost",
    port: Int = 1025,
    private val from: String = "openly@localhost",
    private val username: String? = null,
    private val password: String? = null,
    startTls: Boolean = false
) {
    private val session = Session.getInstance(Properties().apply {
        setProperty("mail.smtp.host", host)
        setProperty("mail.smtp.port", port.toString())
        setProperty("mail.smtp.auth", (username != null).toString())
        setProperty("mail.smtp.starttls.enable", startTls.toString())
        setProperty("mail.smtp.starttls.required", startTls.toString())
        setProperty("mail.smtp.connectiontimeout", "10000")
        setProperty("mail.smtp.timeout", "10000")
        setProperty("mail.smtp.writetimeout", "10000")
    })
    fun send(email: String, purpose: String, token: String) {
        val path = if (purpose == "verify") "/verify" else "/reset-password"
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(this@SmtpLinks.from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(email, true))
            subject = if (purpose == "verify") "Verify your Openly email" else "Reset your Openly password"
            setText("Open this link to continue:\n${baseUrl.trimEnd('/')}$path?token=${URLEncoder.encode(token, UTF_8)}\n\nIf you did not request this, ignore this email.", "UTF-8")
        }
        session.getTransport("smtp").use { transport ->
            transport.connect(username, password)
            transport.sendMessage(message, message.allRecipients)
        }
    }
}
