package com.nova.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ReleaseUpdate(val version: String, val url: String)
internal var releaseEndpoint = "https://api.github.com/repos/benson-singapore/cineo-tv-os/releases/latest"

internal fun isNewerRelease(tag: String, current: String): Boolean {
    fun parts(value: String): List<Int>? {
        val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
    val remote = parts(tag) ?: return false
    val local = parts(current) ?: return false
    return remote.zip(local).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

internal suspend fun checkRelease(current: String): ReleaseUpdate? = withContext(Dispatchers.IO) {
    val connection = URL(releaseEndpoint).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Cineo-TV")
        check(connection.responseCode == 200)
        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = release.getString("tag_name")
        require(Regex("^[vV]?\\d+\\.\\d+\\.\\d+$").matches(tag.trim()))
        require(Regex("^[vV]?\\d+\\.\\d+\\.\\d+$").matches(current.trim()))
        if (isNewerRelease(tag, current)) ReleaseUpdate(tag, "https://github.com/benson-singapore/cineo-tv-os/releases/latest") else null
    } finally {
        connection.disconnect()
    }
}
