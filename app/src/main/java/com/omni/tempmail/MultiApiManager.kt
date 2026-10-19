package com.omni.tempmail

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern

data class EmailMessage(
    val id: String,
    val from: String,
    val subject: String,
    val date: String,
    val body: String,
    val otp: String?
)

class MultiApiManager(private val onStateChanged: (() -> Unit)? = null) {

    companion object {
        private val OTP_KEYWORDS = Pattern.compile(
            "\\b(code|otp|verification|verify|pin|passcode|token|security|confirm|login|auth)\\b",
            Pattern.CASE_INSENSITIVE
        )
        private val OTP_CANDIDATE = Pattern.compile("(?<![0-9A-Za-z#])(\\d{4,8})(?![0-9A-Za-z])")
    }

    private val lock = Any()
    @Volatile var currentProvider: String = "mail.tm" // "mail.tm", "1secmail", "mail.gw"
    @Volatile var currentEmail: String = "Initializing..."

    // State for mail.tm / mail.gw
    private var jwtToken: String? = null
    private var accountId: String? = null
    private var rawPassword = "OmNipAsSwOrD123!"

    // State for 1secmail
    private var secUser = ""
    private var secDomain = ""

    private val seenIds = mutableSetOf<String>()
    private val messages = mutableListOf<EmailMessage>()

    private fun notifyState() {
        onStateChanged?.invoke()
    }

    fun getMessages(): List<EmailMessage> = synchronized(lock) { ArrayList(messages) }

    private fun http(method: String, urlStr: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = if (stream != null) BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() } else ""
            Pair(code, resp.trim())
        } catch (e: Exception) {
            Pair(0, e.message ?: "error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractOtp(subject: String, body: String): String? {
        val combined = "$subject\n$body"
        if (OTP_KEYWORDS.matcher(combined).find()) {
            val m = OTP_CANDIDATE.matcher(combined)
            while (m.find()) {
                val c = m.group(1) ?: continue
                if (c.length == 4 && c in listOf("2023", "2024", "2025", "2026", "2027")) continue
                return c
            }
        }
        return null
    }

    fun switchProvider(provider: String) {
        synchronized(lock) {
            currentProvider = provider
            seenIds.clear()
            messages.clear()
            currentEmail = "Generating..."
        }
        notifyState()
        generateNewAccount()
    }

    fun generateNewAccount(): Boolean {
        val prov = currentProvider
        synchronized(lock) {
            seenIds.clear()
            messages.clear()
        }

        if (prov == "1secmail") {
            return generate1SecMail()
        } else {
            // mail.tm or mail.gw
            val domainHost = if (prov == "mail.gw") "mail.gw" else "mail.tm"
            val apiBase = "https://api.$domainHost"

            try {
                // 1. Get domains
                val (_, domResp) = http("GET", "$apiBase/domains")
                var domain = domainHost
                if (domResp.startsWith("[")) {
                    val arr = JSONArray(domResp)
                    if (arr.length() > 0) domain = arr.getJSONObject(0).getString("domain")
                } else if (domResp.startsWith("{")) {
                    val arr = JSONObject(domResp).optJSONArray("hydra:member")
                    if (arr != null && arr.length() > 0) domain = arr.getJSONObject(0).getString("domain")
                }

                val user = "omni" + UUID.randomUUID().toString().substring(0, 8)
                val email = "$user@$domain"
                val pass = rawPassword

                // 2. Create account
                val payload = JSONObject().put("address", email).put("password", pass).toString()
                val (postCode, _) = http("POST", "$apiBase/accounts", body = payload)
                if (postCode in 200..299 || postCode == 422) {
                    // 3. Get token
                    val tokenPayload = JSONObject().put("address", email).put("password", pass).toString()
                    val (tokCode, tokResp) = http("POST", "$apiBase/token", body = tokenPayload)
                    if (tokCode in 200..299) {
                        val token = JSONObject(tokResp).optString("token")
                        synchronized(lock) {
                            jwtToken = token
                            currentEmail = email
                        }
                        notifyState()
                        return true
                    }
                }
            } catch (_: Exception) {}

            // Fallback to 1secmail if mail.tm/gw fails
            return generate1SecMail()
        }
    }

    private fun generate1SecMail(): Boolean {
        try {
            val resp = http("GET", "https://www.1secmail.com/api/v1/?action=getDomainList")
            if (resp.second.startsWith("[")) {
                val arr = JSONArray(resp.second)
                if (arr.length() > 0) {
                    val domain = arr.getString(0)
                    val user = "omni" + UUID.randomUUID().toString().substring(0, 8)
                    synchronized(lock) {
                        currentProvider = "1secmail"
                        secUser = user
                        secDomain = domain
                        currentEmail = "$user@$domain"
                    }
                    notifyState()
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun pollInbox(): Int {
        val prov = currentProvider
        if (prov == "1secmail") {
            return poll1SecMail()
        } else {
            return pollMailTmGw()
        }
    }

    private fun poll1SecMail(): Int {
        val u: String
        val d: String
        synchronized(lock) {
            u = secUser
            d = secDomain
        }
        if (u.isBlank() || d.isBlank()) return 0

        val (_, resp) = http("GET", "https://www.1secmail.com/api/v1/?action=getMessages&login=$u&domain=$d")
        if (!resp.startsWith("[")) return 0

        try {
            val arr = JSONArray(resp)
            val newItems = mutableListOf<EmailMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotEmpty() && id !in seenIds) {
                    seenIds.add(id)
                    val (_, detail) = http("GET", "https://www.1secmail.com/api/v1/?action=readMessage&login=$u&domain=$d&id=$id")
                    if (detail.startsWith("{")) {
                        val dObj = JSONObject(detail)
                        val from = dObj.optString("from", "Unknown")
                        val subject = dObj.optString("subject", "(no subject)")
                        val date = dObj.optString("date", "")
                        val body = dObj.optString("textBody", "").ifBlank {
                            Html.fromHtml(dObj.optString("htmlBody", ""), Html.FROM_HTML_MODE_LEGACY).toString()
                        }
                        newItems.add(EmailMessage(id, from, subject, date, body, extractOtp(subject, body)))
                    }
                }
            }
            if (newItems.isNotEmpty()) {
                synchronized(lock) {
                    for (item in newItems.reversed()) messages.add(0, item)
                }
                notifyState()
                return newItems.size
            }
        } catch (_: Exception) {}
        return 0
    }

    private fun pollMailTmGw(): Int {
        val token: String
        val domainHost = if (currentProvider == "mail.gw") "mail.gw" else "mail.tm"
        val apiBase = "https://api.$domainHost"

        synchronized(lock) {
            token = jwtToken ?: return 0
        }

        val (_, resp) = http("GET", "$apiBase/messages", headers = mapOf("Authorization" to "Bearer $token"))
        if (!resp.startsWith("[")) return 0

        try {
            val arr = JSONArray(resp)
            val newItems = mutableListOf<EmailMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotEmpty() && id !in seenIds) {
                    seenIds.add(id)
                    val fromObj = obj.optJSONObject("from")
                    val from = fromObj?.optString("address", "Unknown") ?: "Unknown"
                    val subject = obj.optString("subject", "(no subject)")
                    val date = obj.optString("createdAt", "")
                    val intro = obj.optString("intro", "")

                    // Fetch full message
                    val (_, detail) = http("GET", "$apiBase/messages/$id", headers = mapOf("Authorization" to "Bearer $token"))
                    val body = if (detail.startsWith("{")) {
                        val dObj = JSONObject(detail)
                        val text = dObj.optString("text", "")
                        val html = dObj.optString("html", "")
                        if (text.isNotBlank()) text else Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                    } else intro

                    newItems.add(EmailMessage(id, from, subject, date, body, extractOtp(subject, body)))
                }
            }
            if (newItems.isNotEmpty()) {
                synchronized(lock) {
                    for (item in newItems.reversed()) messages.add(0, item)
                }
                notifyState()
                return newItems.size
            }
        } catch (_: Exception) {}
        return 0
    }
}
