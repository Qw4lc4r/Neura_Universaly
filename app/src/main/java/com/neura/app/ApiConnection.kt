package com.neura.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Only normalize known request suffixes; never guess a router's version or host. */
object ApiConnection {
    fun sameOrigin(first: String, second: String): Boolean {
        val a = first.trim().toHttpUrlOrNull() ?: return false
        val b = second.trim().toHttpUrlOrNull() ?: return false
        return a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }

    fun baseUrl(value: String): String {
        val parsed = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Укажи полный HTTPS-адрес API")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null) {
            "Нужен HTTPS-адрес без логина, пароля, параметров и #"
        }
        require(parsed.host != "example.com") { "Укажи адрес своего API вместо примера" }
        val path = parsed.encodedPath.trimEnd('/')
        if (path.isEmpty() && parsed.host == "api.onekey-ai.lol" && parsed.port == 443) {
            return parsed.newBuilder().encodedPath("/v1").build().toString()
        }
        val suffix = listOf("/chat/completions", "/responses", "/messages", "/models")
            .firstOrNull { path.endsWith(it) }
        return parsed.newBuilder().encodedPath(if (suffix == null) path.ifEmpty { "/" }
            else path.removeSuffix(suffix).ifEmpty { "/" }).build().toString().trimEnd('/')
    }

    fun identity(protocol: String, endpoint: String, key: String = ""): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$protocol|${baseUrl(endpoint)}|${key.trim()}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class ConnectionProfile(val id: String, val name: String)
