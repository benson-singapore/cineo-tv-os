package com.nova.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class OrbitPluginConfig(
    val id: String,
    val name: String,
    val pluginId: String,
    val channelId: String,
    val channelLabel: String = channelId,
    val logoUrl: String = "",
    val description: String = ""
)

internal data class OrbitOperation(
    val operation: String,
    val label: String,
    val scope: String? = null,
    val required: Set<String> = emptySet(),
    val optional: Set<String> = emptySet(),
    val targets: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    val pagination: OrbitPagination? = null,
    val executable: Boolean = true
)

internal data class OrbitPagination(
    val style: String,
    val param: String,
    val default: String,
    val requiresNext: Boolean,
    val autoIncrement: Boolean,
    val scope: String? = null
)

internal data class OrbitChannel(
    val id: String,
    val label: String,
    val defaults: Map<String, String>,
    val operations: List<OrbitOperation>,
    val features: JSONObject
)

internal data class OrbitVideoItem(
    val id: String,
    val title: String,
    val summary: String,
    val imageUrl: String,
    val raw: JSONObject
)

internal data class OrbitContentPage(val items: List<OrbitVideoItem>, val next: String?, val hasMore: Boolean)
internal data class OrbitEpisodeItem(val id: String, val title: String, val raw: JSONObject)
internal data class OrbitPlayback(val title: String, val url: String)
internal data class OrbitHubRequest(val url: String, val authorization: String, val body: String?)

internal data class OrbitPageContext(
    val configId: String,
    val channelId: String,
    val operation: String,
    val scope: String? = null,
    val query: String,
    val params: Map<String, String>,
    val target: Map<String, String>,
    val next: String?
)

internal class OrbitApiException(message: String, val status: Int? = null, val deviceRevoked: Boolean = false) : Exception(message)

/** Device-only Orbit adapter. Orbit is contacted exclusively through the bound Hub. */
internal class OrbitPluginRepository(private val credentials: DeviceCredentialStore) {
    fun listPlugins(): List<OrbitPluginConfig> {
        val data = get("/device/orbit-plugins")
        val items = data.optJSONArray("items") ?: throw OrbitApiException("Hub 返回的插件列表结构不受支持")
        return (0 until items.length()).flatMap { index ->
            val item = items.optJSONObject(index) ?: throw OrbitApiException("Hub 插件配置格式不正确")
            val config = parseOrbitPluginConfig(item)
            if (config.id.isBlank() || config.name.isBlank()) throw OrbitApiException("Hub 插件配置缺少名称或 ID")
            if (config.channelId.isNotBlank()) listOf(config)
            else expandOrbitChannels(config, get("/device/orbit-plugins/${pathSegment(config.id)}/capabilities"))
        }
    }

    fun capabilities(config: OrbitPluginConfig): OrbitChannel {
        val data = get("/device/orbit-plugins/${pathSegment(config.id)}/capabilities")
        return parseOrbitChannel(data, config.channelId)
    }

    fun invoke(config: OrbitPluginConfig, context: OrbitPageContext): JSONObject {
        require(context.configId == config.id && context.channelId == config.channelId) { "分页上下文已过期，请重新加载" }
        val body = JSONObject()
            .put("channel_id", context.channelId)
            .put("operation", context.operation)
            .put("query", context.query)
            .put("params", JSONObject(context.params))
            .put("target", JSONObject(context.target))
        context.scope?.let { body.put("scope", it) }
        context.next?.let { raw -> body.put("next", JSONObject(raw)) }
        val data = request("POST", "/device/orbit-plugins/${pathSegment(config.id)}/invoke", body)
        if (!data.has("data") || data.isNull("data")) throw OrbitApiException("Orbit 返回了空业务数据")
        return data.optJSONObject("data") ?: throw OrbitApiException("Orbit 返回的业务结构不受支持")
    }

    fun listItems(data: JSONObject): OrbitContentPage {
        return parseOrbitItems(data)
    }

    fun listEpisodes(data: JSONObject): List<OrbitEpisodeItem> {
        return parseOrbitEpisodes(data)
    }

    fun playback(data: JSONObject): OrbitPlayback {
        return parseOrbitPlayback(data)
    }

    private fun get(path: String): JSONObject = request("GET", path, null)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        if (!credentials.isBound || credentials.revoked) throw OrbitApiException("请先绑定或重新绑定 Cineo Hub 账号")
        val token = credentials.readToken() ?: throw OrbitApiException("Hub 设备凭证失效，请重新绑定")
        val base = credentials.hubUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
            ?: throw OrbitApiException("未配置 Hub 地址")
        val request = createOrbitHubRequest(base, path, token, body?.toString())
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", request.authorization)
            if (request.body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return try {
                parseHubDataEnvelope(status, text)
            } catch (failure: OrbitApiException) {
                if (failure.deviceRevoked) credentials.markRevoked()
                throw failure
            }
        } catch (failure: OrbitApiException) {
            throw failure
        } catch (_: Exception) {
            throw OrbitApiException("连接 Hub 失败，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    private fun pathSegment(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}

internal fun parseOrbitPluginConfig(item: JSONObject): OrbitPluginConfig = OrbitPluginConfig(
    id = item.optString("id"), name = item.optString("name"),
    pluginId = item.optString("plugin_id"), channelId = item.optString("channel_id"),
    logoUrl = safeOrbitLogoUri(item.opt("logo_url") as? String),
    description = (item.opt("description") as? String)?.trim().orEmpty()
)

internal fun safeOrbitLogoUri(raw: String?): String {
    val uri = runCatching { java.net.URI(raw?.trim().orEmpty()) }.getOrNull() ?: return ""
    return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() &&
        it.userInfo == null && it.rawQuery == null && it.rawFragment == null }?.toASCIIString().orEmpty()
}

internal fun expandOrbitChannels(config: OrbitPluginConfig, data: JSONObject): List<OrbitPluginConfig> {
    val plugin = data.optJSONObject("plugin") ?: throw OrbitApiException("Hub 返回的能力结构不受支持")
    if (!plugin.optBoolean("executable", true)) throw OrbitApiException("此 Orbit 插件当前不可执行")
    val channels = plugin.optJSONArray("channels") ?: throw OrbitApiException("Hub 未返回频道能力")
    val result = (0 until channels.length()).mapNotNull { index ->
        val channel = channels.optJSONObject(index) ?: return@mapNotNull null
        val id = channel.optString("id")
        if (id.isBlank() || !channel.optBoolean("executable", true)) null
        else config.copy(channelId = id, channelLabel = channel.optString("label").ifBlank { id })
    }.distinctBy { it.channelId }
    if (result.isEmpty()) throw OrbitApiException("此 Orbit 插件没有可用频道")
    return result
}

internal fun parseOrbitChannel(data: JSONObject, channelId: String): OrbitChannel {
    val plugin = data.optJSONObject("plugin") ?: throw OrbitApiException("Hub 返回的能力结构不受支持")
    if (!plugin.optBoolean("executable", true)) throw OrbitApiException("此 Orbit 插件当前不可执行")
    val channels = plugin.optJSONArray("channels") ?: throw OrbitApiException("Hub 未返回频道能力")
    val channel = (0 until channels.length()).asSequence().mapNotNull { channels.optJSONObject(it) }
        .firstOrNull { it.optString("id") == channelId }
        ?: throw OrbitApiException("配置绑定的频道不在能力列表中")
    if (!channel.optBoolean("executable", true)) throw OrbitApiException("此频道当前不可执行")
    val ops = channel.optJSONArray("operations") ?: JSONArray()
    val operations = (0 until ops.length()).mapNotNull { ops.optJSONObject(it)?.toOrbitOperation() }
        .filter { it.executable }
    return OrbitChannel(
        id = channel.optString("id"), label = channel.optString("label", channel.optString("id")),
        defaults = channel.optJSONObject("params").orbitStringMap() + channel.optJSONObject("defaults").orbitStringMap(),
        operations = operations, features = channel.optJSONObject("features") ?: JSONObject()
    )
}

internal fun parseHubDataEnvelope(httpStatus: Int, raw: String): JSONObject {
    val envelope = runCatching { JSONObject(raw) }.getOrElse { throw OrbitApiException("无法读取 Hub 响应", httpStatus) }
    if (httpStatus == 401 && envelope.optInt("code") == 40100) throw OrbitApiException("Hub 设备凭证已失效，请重新绑定", httpStatus, deviceRevoked = true)
    if (httpStatus !in 200..299 || envelope.optInt("code") != 200) {
        val message = envelope.optString("message").takeIf(String::isNotBlank) ?: "Hub 请求失败（$httpStatus）"
        throw OrbitApiException(message, httpStatus)
    }
    return envelope.optJSONObject("data") ?: throw OrbitApiException("Hub 返回了空响应")
}

private fun JSONObject?.orbitStringMap(): Map<String, String> = this?.keys()?.asSequence()?.associateWith { key ->
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) "" else value.toString()
}.orEmpty()

private fun JSONObject.toOrbitOperation(): OrbitOperation? {
    val id = optString("operation").ifBlank { return null }
    val required = orbitNamedFields(optJSONArray("required"))
    val optional = orbitNamedFields(optJSONArray("optional"))
    val targetFields = optJSONArray("target")
    val targets = (0 until (targetFields?.length() ?: 0)).mapNotNull { index ->
        val field = targetFields?.optJSONObject(index) ?: return@mapNotNull null
        val key = field.optString("field").ifBlank { field.optString("name") }
        val paramName = field.optString("name").ifBlank { key }
        key.takeIf(String::isNotBlank)?.let { it to paramName }
    }.toMap()
    val paginationJson = optJSONObject("pagination")
    return OrbitOperation(
        operation = id, label = optString("label").ifBlank { id },
        scope = optString("scope").takeIf(String::isNotBlank), required = required, optional = optional,
        targets = targets, defaults = optJSONObject("defaults").orbitStringMap(),
        pagination = paginationJson?.let {
            OrbitPagination(it.optString("style"), it.optString("param"), it.optString("default", "1"),
                it.optBoolean("requiresNext", false), it.optBoolean("autoIncrement", false),
                it.optString("scope").takeIf(String::isNotBlank))
        }, executable = optBoolean("executable", true)
    )
}

private fun orbitNamedFields(fields: JSONArray?): Set<String> = (0 until (fields?.length() ?: 0)).mapNotNull { index ->
    when (val field = fields?.opt(index)) {
        is JSONObject -> field.optString("name").takeIf(String::isNotBlank)
        is String -> field.takeIf(String::isNotBlank)
        else -> null
    }
}.toSet()

internal fun safeMediaUri(raw: String): String? {
    val parsed = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (parsed.isOpaque || parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank()) return null
    if (parsed.userInfo != null) return null
    return parsed.toASCIIString()
}

internal fun createOrbitHubRequest(hubBase: String, path: String, deviceToken: String, body: String? = null): OrbitHubRequest {
    val base = hubBase.trim().trimEnd('/')
    require(base.startsWith("https://", ignoreCase = true) || base.startsWith("http://", ignoreCase = true)) { "Hub 地址需以 http:// 或 https:// 开头" }
    require(path == "/device/orbit-plugins" || path.startsWith("/device/orbit-plugins/")) { "Orbit 设备请求只能发往 Hub 设备接口" }
    val apiBase = if (base.endsWith("/api/v1")) base else "$base/api/v1"
    return OrbitHubRequest("$apiBase$path", "Bearer $deviceToken", body)
}

private fun safeImageUri(raw: String): String {
    val parsed = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return ""
    return parsed.takeIf { it.scheme?.lowercase() == "https" && !it.host.isNullOrBlank() }?.toASCIIString().orEmpty()
}

internal fun nextPageContext(previous: OrbitPageContext, nextRaw: String?, pagination: OrbitPagination?): OrbitPageContext? {
    if (nextRaw.isNullOrBlank()) return null
    // The cursor itself is opaque. Pagination declarations govern which operation
    // may consume it; they never authorize the client to rewrite its JSON.
    return previous.copy(next = nextRaw)
}

internal fun paginationContext(
    previous: OrbitPageContext,
    pageOperation: OrbitOperation,
    channelDefaults: Map<String, String>,
    overrides: Map<String, String>,
    cursor: String?
): OrbitPageContext {
    val accepted = pageOperation.required + pageOperation.optional - "query"
    val params = (previous.params + channelDefaults + pageOperation.defaults + overrides).filterKeys { it in accepted }
    return previous.copy(
        operation = pageOperation.operation,
        scope = pageOperation.scope ?: pageOperation.pagination?.scope,
        query = previous.query,
        params = params,
        target = previous.target,
        next = cursor
    )
}

internal fun parseOrbitItems(data: JSONObject): OrbitContentPage {
    val items = data.optJSONArray("items") ?: throw OrbitApiException("此 Orbit 响应没有受支持的 items 列表")
    val mapped = (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").ifBlank { item.optString("itemKey") }
        val title = item.optString("title")
        if (id.isBlank() || title.isBlank()) return@mapNotNull null
        OrbitVideoItem(id, title, item.optString("summary").ifBlank { item.optString("content") },
            safeImageUri(item.optString("image").ifBlank { item.optString("cover") }), item)
    }
    if (items.length() > 0 && mapped.isEmpty()) throw OrbitApiException("Orbit items 字段与已支持的项目结构不匹配")
    val cursor = data.optJSONObject("next")?.toString()
    val hasMore = cursor != null && mapped.isNotEmpty() && data.optBoolean("hasMore", true)
    return OrbitContentPage(mapped, cursor.takeIf { hasMore }, hasMore)
}

internal fun parseOrbitEpisodes(data: JSONObject): List<OrbitEpisodeItem> {
    val items = data.optJSONArray("items") ?: throw OrbitApiException("此 Orbit 响应没有受支持的章节列表")
    val mapped = (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").ifBlank { item.optString("itemKey") }
        val title = item.optString("title")
        if (id.isBlank() || title.isBlank()) null else OrbitEpisodeItem(id, title, item)
    }
    if (items.length() > 0 && mapped.isEmpty()) throw OrbitApiException("Orbit 章节字段与已支持的项目结构不匹配")
    return mapped
}

internal fun parseOrbitPlayback(data: JSONObject): OrbitPlayback {
    val item = data.optJSONArray("items")?.optJSONObject(0) ?: data
    val uri = safeMediaUri(item.optString("url"))
        ?: throw OrbitApiException("此章节没有受支持的 HTTPS 播放地址")
    return OrbitPlayback(item.optString("title").ifBlank { "正在播放" }, uri)
}

