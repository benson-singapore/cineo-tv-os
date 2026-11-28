package com.nova.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import android.os.SystemClock

/** Per-device choices and last successfully retrieved plugin list, scoped to a binding. */
internal class OrbitLocalSettings(context: Context, private val credentials: DeviceCredentialStore) {
    private val prefs = context.applicationContext.getSharedPreferences("orbit_local_settings", Context.MODE_PRIVATE)
    private var unlockedUntil = 0L
    var enabled: Boolean
        get() = prefs.getBoolean("orbit_enabled", true)
        set(value) { prefs.edit().putBoolean("orbit_enabled", value).apply(); unlockedUntil = 0L }
    var timeoutMinutes: Int
        get() = prefs.getInt("password_timeout", 5).coerceIn(1, 60)
        set(value) { prefs.edit().putInt("password_timeout", value.coerceIn(1, 60)).apply(); unlockedUntil = 0L }
    private val passwordFile = java.io.File(context.applicationContext.filesDir, "orbit-password.v1")
    private val passwords = OrbitPasswordStore(
        readRecord = { if (passwordFile.exists()) passwordFile.readText(Charsets.UTF_8) else null },
        writeRecord = { writeOrbitPasswordRecord(passwordFile, it) },
        legacyRecord = {
            val salt = prefs.getString("password_salt", null)
            val hash = prefs.getString("password_hash", null)
            if (salt == null && hash == null) null else if (salt != null && hash != null) "$salt:$hash" else "invalid"
        }
    )
    val hasPassword get() = passwords.hasPassword
    fun unlocked() = !hasPassword || SystemClock.elapsedRealtime() < unlockedUntil
    fun verify(password: String) = passwords.verify(password)
    fun unlock(password: String): Boolean {
        if (!hasPassword || !verify(password)) return false
        unlockedUntil = SystemClock.elapsedRealtime() + timeoutMinutes * 60_000L
        return true
    }
    fun setPassword(password: String): Boolean {
        if (!passwords.save(password)) return false
        unlockedUntil = 0L
        return true
    }
    fun bindingScope(): String? = scope()
    fun capabilities(id: String): JSONObject? = scope()?.let { key -> prefs.getString("$key:capabilities:$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() } }
    private fun scope(): String? {
        if (!credentials.isBound || credentials.revoked) return null
        val hub = credentials.hubUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val device = credentials.deviceId?.takeIf { it.isNotBlank() } ?: return null
        return MessageDigest.getInstance("SHA-256").digest("$hub\u0000$device\u0000${credentials.bindingInstance}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun key(id: String) = "${scope()}:enabled:$id"
    fun isEnabled(id: String): Boolean = scope() != null && prefs.getBoolean(key(id), true)
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val scope = scope() ?: return false
        return prefs.edit().putBoolean("$scope:enabled:$id", enabled).commit()
    }
    fun saveSnapshot(binding: String?, plugins: List<OrbitPluginConfig>, capabilities: Map<String, JSONObject>) {
        val scope = scope() ?: throw OrbitApiException("设备绑定已变化，请重新同步")
        if (scope != binding) throw OrbitApiException("设备绑定已变化，请重新同步")
        val items = JSONArray()
        plugins.forEach { items.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("plugin_id", it.pluginId).put("channel_id", it.channelId).put("channel_label", it.channelLabel)
            .put("logo_url", it.logoUrl).put("description", it.description)) }
        val editor = prefs.edit().putString("$scope:plugins", items.toString())
        capabilities.forEach { (id, data) -> editor.putString("$scope:capabilities:$id", data.toString()) }
        if (!editor.commit()) throw OrbitApiException("保存 Orbit 缓存失败")
    }
    fun plugins(): List<OrbitPluginConfig> {
        val scope = scope() ?: return emptyList()
        return runCatching {
            val items = JSONArray(prefs.getString("$scope:plugins", "[]"))
            (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                parseOrbitPluginConfig(item).copy(channelLabel = item.optString("channel_label").ifBlank { item.optString("channel_id") })
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
