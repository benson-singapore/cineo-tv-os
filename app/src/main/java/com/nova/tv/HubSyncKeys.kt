package com.nova.tv

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** Matches the API contract's cross-device source key normalization. */
internal fun hubSourceKey(rawUrl: String): String {
    val uri = URI(rawUrl.trim()).normalize()
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("站点地址缺少协议")
    val host = uri.host?.lowercase(Locale.ROOT) ?: error("站点地址无效")
    val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
    val path = uri.rawPath.orEmpty().trimEnd('/').ifBlank { "" }
    val query = uri.rawQuery?.split('&')?.filter(String::isNotBlank)?.sorted()?.joinToString("&")
    val canonical = buildString {
        append(scheme).append("://").append(host)
        if (uri.port >= 0 && !defaultPort) append(':').append(uri.port)
        append(path)
        if (!query.isNullOrBlank()) append('?').append(query)
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun syncEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
