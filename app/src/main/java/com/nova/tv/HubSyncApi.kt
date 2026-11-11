package com.nova.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class HubHttpReply(val status: Int, val envelope: HubEnvelopeResult, val retryAfterSeconds: Long?)

internal class DeviceCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("cineo_hub_device", Context.MODE_PRIVATE)
    val isBound: Boolean get() = preferences.contains(DEVICE_ID) && readToken() != null
    val deviceId: String? get() = preferences.getString(DEVICE_ID, null)
    val hubUrl: String? get() = preferences.getString(HUB_URL, null)
    val lastSyncAt: Long get() = preferences.getLong(LAST_SYNC_AT, 0L)
    val lastFailure: String? get() = preferences.getString(LAST_FAILURE, null)
    val revoked: Boolean get() = preferences.getBoolean(REVOKED, false)
    val lastCursor: Long get() = preferences.getLong(CURSOR, 0L)
    val generation: Long get() = preferences.getLong(GENERATION, 0L)

    fun saveHubAddress(url: String) { preferences.edit().putString(HUB_URL, url.trim().trimEnd('/')).apply() }
    fun save(hubUrl: String, deviceId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(HUB_URL, hubUrl).putString(DEVICE_ID, deviceId)
            .putString(TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putBoolean(REVOKED, false).putString(LAST_FAILURE, null).putLong(CURSOR, 0L).putLong(GENERATION, 0L).apply()
    }

    fun readToken(): String? {
        return try {
            val iv = preferences.getString(TOKEN_IV, null) ?: return null
            val encrypted = preferences.getString(TOKEN_CIPHER, null) ?: return null
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun writeCursor(cursor: Long, generation: Long = this.generation) {
        preferences.edit().putLong(CURSOR, cursor).putLong(GENERATION, generation).apply()
    }
    fun markSuccess(now: Long) { preferences.edit().putLong(LAST_SYNC_AT, now).putString(LAST_FAILURE, null).apply() }
    fun markFailure(message: String) { preferences.edit().putString(LAST_FAILURE, safeFailureMessage(message)).apply() }
    fun markRevoked() { preferences.edit().putBoolean(REVOKED, true).putString(LAST_FAILURE, null).apply() }
    fun clearLocalBinding() {
        preferences.edit().remove(DEVICE_ID).remove(TOKEN_IV).remove(TOKEN_CIPHER)
            .remove(CURSOR).remove(GENERATION).remove(REVOKED).remove(LAST_FAILURE).remove(LAST_SYNC_AT).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "cineo_hub_device_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HUB_URL = "hub_url"
        const val DEVICE_ID = "device_id"
        const val TOKEN_IV = "token_iv"
        const val TOKEN_CIPHER = "token_ciphertext"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_FAILURE = "last_failure"
        const val CURSOR = "cursor"
        const val GENERATION = "generation"
        const val REVOKED = "revoked"
    }
}

internal class HubSyncApi(private val credentials: DeviceCredentialStore) {
    fun bind(baseUrl: String, pin: String, deviceName: String): JSONObject {
        val reply = request("POST", endpoint(baseUrl, "/device/bind"), JSONObject()
            .put("pin", pin).put("device_name", deviceName.take(64)).put("device_type", "android_tv"), token = null)
        return reply.requireData()
    }

    fun push(items: List<SyncEnvelope>, trigger: SyncTrigger): HubHttpReply = request("POST", path("/device/sync/push"), JSONObject().put("items", JSONArray().apply {
        items.forEach { put(it.toJson()) }
    }), trigger = trigger)

    fun pull(since: Long, trigger: SyncTrigger): HubHttpReply = request("GET", path("/device/sync/pull?since=$since&limit=500"), trigger = trigger)

    fun rebuild(knownGeneration: Long, cursor: Long = 0L, limit: Int = 500, trigger: SyncTrigger): HubHttpReply = request(
        "POST", path("/device/sync/rebuild"), createRebuildRequest(knownGeneration, cursor, limit), trigger = trigger
    )

    fun settings(trigger: SyncTrigger): HubHttpReply = request("GET", path("/device/settings"), trigger = trigger)

    private fun path(path: String): String = endpoint(credentials.hubUrl ?: error("Hub 地址未设置"), path)

    private fun request(method: String, url: String, body: JSONObject? = null, token: String? = credentials.readToken(), trigger: SyncTrigger? = null): HubHttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (trigger != null) connection.setRequestProperty("X-Sync-Trigger", trigger.value)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..399) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
            val parsed = parseHubEnvelope(response, status)
            val normalized = if (!parsed.successful && parsed.failure is HubFailure.Api && retryAfter != null) {
                parsed.copy(failure = parsed.failure.copy(retryAfterSeconds = retryAfter))
            } else parsed
            return HubHttpReply(status, normalized, retryAfter)
        } finally { connection.disconnect() }
    }

    private fun HubHttpReply.requireData(): JSONObject {
        if (!envelope.successful) throw HubApiException(envelope.failure ?: HubFailure.Api(status, "绑定失败"))
        return envelope.data ?: JSONObject()
    }

    private fun endpoint(rawBase: String, path: String): String {
        val base = rawBase.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "Hub 地址需以 http:// 或 https:// 开头" }
        val apiBase = if (base.endsWith("/api/v1")) base else "$base/api/v1"
        return apiBase + path
    }
}

internal data class SyncStatus(val label: String, val detail: String? = null, val at: Long = System.currentTimeMillis())
internal class HubApiException(val failure: HubFailure) : Exception("Hub 请求失败")

internal fun SyncEnvelope.toJson(): JSONObject = JSONObject().put("entity", entity).put("key", key)
    .put("updated_at", updatedAt).put("deleted", deleted).put("payload", payload?.let(::JSONObject))

internal fun JSONObject.toSyncEnvelope(deviceId: String = ""): SyncEnvelope = SyncEnvelope(
    entity = optString("entity"), key = optString("key"), updatedAt = optLong("updated_at"),
    deleted = optBoolean("deleted"), payload = optJSONObject("payload")?.toMap(), deviceId = optString("device_id", deviceId)
)

internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> (0 until value.length()).map { index ->
            val item = value.opt(index)
            if (item is JSONObject) item.toMap() else if (item == JSONObject.NULL) null else item
        }
        else -> value
    }
}

private fun safeFailureMessage(message: String): String = message
    .replace(Regex("(?i)(pin|device_token|token|tmdb_key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[已隐藏]")
    .take(180)
