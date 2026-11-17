package com.neura.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class Message(val id: String = UUID.randomUUID().toString(), val role: String, val text: String, val timestamp: Long = System.currentTimeMillis())
data class Conversation(val id: String = UUID.randomUUID().toString(), val title: String = "Новый чат", val messages: List<Message> = emptyList(), val updatedAt: Long = System.currentTimeMillis())

class LocalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("neura_settings", Context.MODE_PRIVATE)
    fun string(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun bool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun loadKey(provider: String = "anthropic"): String = runCatching {
        val encoded = string("encrypted_api_key_$provider", if (provider == "anthropic") string("encrypted_api_key") else "")
        if (encoded.isBlank()) return@runCatching ""
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrDefault("")
    fun saveKey(value: String, provider: String = "anthropic") {
        if (value.isBlank()) { prefs.edit().remove("encrypted_api_key_$provider").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        set("encrypted_api_key_$provider", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "neura_secret_v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun loadChats(): List<Conversation> = runCatching {
        val file = context.getFileStreamPath("chats.json")
        if (!file.exists()) {
            // One-off migration of older prototype storage.
            val legacy = string("chats", "[]")
            val arr = JSONArray(legacy)
            val migrated = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); val messages = o.optJSONArray("messages") ?: JSONArray()
                Conversation(o.optString("id", UUID.randomUUID().toString()), o.optString("title", "Чат"),
                    (0 until messages.length()).map { j -> val m = messages.getJSONObject(j); Message(role = m.optString("role", "user"), text = m.optString("text")) })
            }
            if (migrated.isNotEmpty()) saveChats(migrated)
            migrated
        } else decodeChats(file.readText())
    }.getOrDefault(emptyList())
    fun saveChats(chats: List<Conversation>) {
        val json = JSONArray()
        chats.forEach { chat ->
            val messages = JSONArray()
            chat.messages.forEach { m -> messages.put(JSONObject().put("id",m.id).put("role",m.role).put("text",m.text).put("timestamp",m.timestamp)) }
            json.put(JSONObject().put("id",chat.id).put("title",chat.title).put("updatedAt",chat.updatedAt).put("messages",messages))
        }
        // Atomic replacement: an interrupted write never truncates the existing file.
        val atomic = android.util.AtomicFile(context.getFileStreamPath("chats.json"))
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun decodeChats(raw: String): List<Conversation> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val messages = o.optJSONArray("messages") ?: JSONArray()
            Conversation(o.getString("id"), o.optString("title", "Чат"), (0 until messages.length()).map { j ->
                val m = messages.getJSONObject(j)
                Message(m.optString("id", UUID.randomUUID().toString()), m.getString("role"), m.optString("text"), m.optLong("timestamp", 0L))
            }, o.optLong("updatedAt", 0L))
        }.sortedByDescending { it.updatedAt }
    }
}
