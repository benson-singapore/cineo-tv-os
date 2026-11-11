package com.nova.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TmdbPerson(val name: String, val role: String, val profileUrl: String)
data class TmdbEpisodeMetadata(
    val number: Int,
    val name: String,
    val overview: String,
    val stillUrl: String,
    val rating: Double
)
data class TmdbSeasonMetadata(
    val number: Int,
    val name: String,
    val overview: String,
    val posterUrl: String,
    val episodes: List<TmdbEpisodeMetadata>
)
data class TmdbTvMetadata(
    val title: String,
    val originalTitle: String,
    val overview: String,
    val year: String,
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val directors: List<TmdbPerson>,
    val cast: List<TmdbPerson>,
    val seasons: List<TmdbSeasonMetadata>
)

/** Stores the TMDB bearer token encrypted with an Android Keystore AES key. */
class TmdbSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("cineo_tv_tmdb", Context.MODE_PRIVATE)
    internal val database = CineoDatabase(context.applicationContext)

    val cacheRetentionDays: Int
        get() = preferences.getInt(CACHE_RETENTION_DAYS, DEFAULT_CACHE_RETENTION_DAYS)
            .coerceIn(MIN_CACHE_RETENTION_DAYS, MAX_CACHE_RETENTION_DAYS)

    fun saveCacheRetentionDays(days: Int) {
        preferences.edit().putInt(
            CACHE_RETENTION_DAYS,
            days.coerceIn(MIN_CACHE_RETENTION_DAYS, MAX_CACHE_RETENTION_DAYS)
        ).apply()
    }

    fun isConfigured(): Boolean =
        preferences.contains(TOKEN_CIPHERTEXT) && preferences.contains(TOKEN_IV)

    fun saveToken(token: String) {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "请输入有效的 TMDB API Token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun clearToken() {
        preferences.edit().remove(TOKEN_IV).remove(TOKEN_CIPHERTEXT).apply()
    }

    internal fun readToken(): String? {
        val iv = preferences.getString(TOKEN_IV, null) ?: return null
        val ciphertext = preferences.getString(TOKEN_CIPHERTEXT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "cineo_tv_tmdb_token"
        const val TOKEN_IV = "token_iv"
        const val TOKEN_CIPHERTEXT = "token_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CACHE_RETENTION_DAYS = "cache_retention_days"
        const val DEFAULT_CACHE_RETENTION_DAYS = 7
        const val MIN_CACHE_RETENTION_DAYS = 1
        const val MAX_CACHE_RETENTION_DAYS = 30
    }
}

/** Fetches details on demand and shares cached metadata with browsing screens. */
class TmdbMetadataRepository(private val settings: TmdbSettingsStore) {
    private val database = settings.database

    private val revision = MutableStateFlow(0L)
    val cacheRevision = revision.asStateFlow()

    fun clearCache() {
        database.clearTmdbMetadataCache()
        revision.update { it + 1 }
    }

    /** Cache-only lookup: browsing never triggers a TMDB search or season download. */
    suspend fun cachedDetails(video: RemoteVideo): TmdbTvMetadata? = withContext(Dispatchers.IO) {
        readCachedDetails(video, System.currentTimeMillis())
    }

    private fun cacheKey(video: RemoteVideo): String =
        normalize(video.title).ifBlank { video.title.trim().lowercase() }

    private fun readCachedDetails(video: RemoteVideo, now: Long): TmdbTvMetadata? {
        val key = cacheKey(video)
        val (json, lastAccessedAt) = database.readTmdbMetadataCache(key) ?: return null
        if (now - lastAccessedAt >= cacheRetentionMillis()) {
            database.deleteTmdbMetadataCache(key)
            return null
        }
        val metadata = runCatching { JSONObject(json).toTmdbTvMetadata() }.getOrNull()
        if (metadata == null) database.deleteTmdbMetadataCache(key)
        else database.touchTmdbMetadataCache(key, now)
        return metadata
    }

    fun pruneExpiredCache() {
        database.deleteExpiredTmdbMetadataCache(System.currentTimeMillis() - cacheRetentionMillis())
    }

    suspend fun validateToken(token: String) = withContext(Dispatchers.IO) {
        request("configuration", token.trim(), mapOf("language" to configuredLanguage()))
        Unit
    }

    suspend fun loadDetails(video: RemoteVideo): TmdbTvMetadata? {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val retentionMillis = cacheRetentionMillis()
            val cacheKey = cacheKey(video)
            database.deleteExpiredTmdbMetadataCache(now - retentionMillis)
            readCachedDetails(video, now)?.let { return@withContext it }
            val token = settings.readToken() ?: return@withContext null
            val year = video.year.take(4).toIntOrNull()
            val searchTitle = searchTitle(video.title)
            val searchParameters = linkedMapOf(
                "query" to searchTitle,
                "language" to configuredLanguage(),
                "include_adult" to includeAdultContent().toString()
            )
            if (year != null) searchParameters["first_air_date_year"] = year.toString()
            var matches = request("search/tv", token, searchParameters).array("results")
            fun chooseMatch(candidates: List<JSONObject>): JSONObject? = candidates.mapNotNull { candidate ->
                val id = candidate.optInt("id", 0)
                if (id <= 0) null else candidate to matchScore(video, candidate)
            }.maxByOrNull { it.second }
                ?.takeIf { it.second >= MIN_MATCH_SCORE }?.first
            var match = chooseMatch(matches)
            if (match == null && year != null) {
                matches = request("search/tv", token, mapOf("query" to searchTitle, "language" to configuredLanguage(), "include_adult" to includeAdultContent().toString()))
                    .array("results")
                match = chooseMatch(matches)
            }
            match ?: return@withContext null

            val id = match.optInt("id")
            val details = request(
                "tv/$id",
                token,
                mapOf("language" to configuredLanguage(), "append_to_response" to "credits,aggregate_credits")
            )
            val seasonNumbers = details.array("seasons")
                .map { it.optInt("season_number", -1) }
                .filter { it > 0 }
                .distinct()
            val seasons = coroutineScope {
                seasonNumbers.map { number -> async {
                    runCatching { readSeason(id, number, token) }.getOrNull()
                } }.awaitAll().filterNotNull()
            }
            val credits = details.optJSONObject("credits") ?: JSONObject()
            val aggregateCredits = details.optJSONObject("aggregate_credits") ?: JSONObject()
            val crew = aggregateCredits.array("crew").ifEmpty { credits.array("crew") }
            val directors = crew.asSequence()
                .filter { member ->
                    member.optString("department").equals("Directing", ignoreCase = true) ||
                        member.optString("job").equals("Director", ignoreCase = true) ||
                        member.array("jobs").any { it.optString("job").equals("Director", ignoreCase = true) }
                }
                .distinctBy { it.optInt("id") }
                .map { member ->
                    val jobs = member.array("jobs").map { it.optString("job") }.filter(String::isNotBlank)
                    person(member, jobs.firstOrNull() ?: member.optString("job").ifBlank { "导演" })
                }
                .filter { it.name.isNotBlank() }
                .toList()
            val castEntries = aggregateCredits.array("cast").ifEmpty { credits.array("cast") }
            val cast = castEntries.take(16)
                .map { member ->
                    val character = member.optString("character").ifBlank {
                        member.array("roles").firstOrNull()?.optString("character").orEmpty()
                    }
                    person(member, character)
                }
                .filter { it.name.isNotBlank() }
            val result = TmdbTvMetadata(
                title = details.optString("name").ifBlank { match.optString("name") },
                originalTitle = details.optString("original_name"),
                overview = details.optString("overview"),
                year = details.optString("first_air_date").take(4),
                posterUrl = imageUrl(details.optString("poster_path"), "w500"),
                backdropUrl = imageUrl(details.optString("backdrop_path"), "w1280"),
                rating = details.optDouble("vote_average", 0.0),
                genres = details.array("genres").map { it.optString("name") }.filter(String::isNotBlank),
                runtimeMinutes = details.optJSONArray("episode_run_time")
                    ?.takeIf { it.length() > 0 }?.optInt(0)?.takeIf { it > 0 },
                directors = directors,
                cast = cast,
                seasons = seasons
            )
            database.writeTmdbMetadataCache(cacheKey, result.toJson().toString(), now)
            revision.update { it + 1 }
            result
        }
    }

    private fun cacheRetentionMillis(): Long =
        settings.cacheRetentionDays * MILLIS_PER_DAY

    private fun configuredLanguage(): String = settings.database.readAppSetting("hub.tmdb_language")
        ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) } ?: "zh-CN"

    private fun includeAdultContent(): Boolean = settings.database.readAppSetting("hub.include_adult") == "true"

    private fun readSeason(tvId: Int, seasonNumber: Int, token: String): TmdbSeasonMetadata {
        val data = request("tv/$tvId/season/$seasonNumber", token, mapOf("language" to configuredLanguage()))
        val episodes = data.array("episodes").mapNotNull { episode ->
            val number = episode.optInt("episode_number", 0)
            if (number <= 0) null else TmdbEpisodeMetadata(
                number = number,
                name = episode.optString("name").ifBlank { "第${number}集" },
                overview = episode.optString("overview"),
                stillUrl = imageUrl(episode.optString("still_path"), "w780"),
                rating = episode.optDouble("vote_average", 0.0)
            )
        }
        return TmdbSeasonMetadata(
            number = seasonNumber,
            name = data.optString("name").ifBlank { "第${seasonNumber}季" },
            overview = data.optString("overview"),
            posterUrl = imageUrl(data.optString("poster_path"), "w500"),
            episodes = episodes
        )
    }

    private fun request(path: String, token: String, parameters: Map<String, String>): JSONObject {
        val builder = android.net.Uri.parse("https://api.themoviedb.org/3/").buildUpon()
        path.split('/').filter(String::isNotBlank).forEach { builder.appendPath(it) }
        parameters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        val uri = builder.build()
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("User-Agent", "CineoTV/1.0 Android")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val reason = when (status) {
                    401, 403 -> "TMDB Token 无效或无权访问"
                    404 -> "TMDB 未找到对应剧集"
                    429 -> "TMDB 请求过于频繁，请稍后重试"
                    else -> "TMDB 请求失败（HTTP $status）"
                }
                throw IllegalStateException(reason)
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun matchScore(video: RemoteVideo, candidate: JSONObject): Int {
        val query = normalize(video.title)
        val names = listOf(candidate.optString("name"), candidate.optString("original_name"))
            .map(::normalize).filter(String::isNotBlank)
        val titleScore = names.maxOfOrNull { name ->
            when {
                name == query -> 100
                name.startsWith(query) || query.startsWith(name) -> 75
                name.contains(query) || query.contains(name) -> 55
                else -> 0
            }
        } ?: 0
        val sourceYear = video.year.take(4).toIntOrNull()
        val tmdbYear = candidate.optString("first_air_date").take(4).toIntOrNull()
        val yearPenalty = if (sourceYear != null && tmdbYear != null) (kotlin.math.abs(sourceYear - tmdbYear) * 3).coerceAtMost(30) else 0
        return titleScore - yearPenalty + candidate.optDouble("popularity", 0.0).toInt().coerceAtMost(4)
    }

    private fun person(raw: JSONObject, role: String) = TmdbPerson(
        name = raw.optString("name"),
        role = role,
        profileUrl = imageUrl(raw.optString("profile_path"), "w185")
    )

    private fun imageUrl(path: String, size: String): String =
        path.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/$size/${it.removePrefix("/")}" }.orEmpty()

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("第\\s*\\d+\\s*季|s\\d{1,2}(?:e\\d{1,3})?"), "")
        .replace(Regex("\\b(?:19|20)\\d{2}\\b"), "")
        .replace(Regex("[\\s\\p{Punct}·]+"), "")

    private fun searchTitle(value: String): String = value
        .replace(Regex("(?i)第\\s*\\d+\\s*季|S\\d{1,2}(?:E\\d{1,3})?"), " ")
        .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
        .trim()

    private fun JSONObject.array(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add) }
    }

    private companion object {
        const val MIN_MATCH_SCORE = 50
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

private fun TmdbTvMetadata.toJson(): JSONObject = JSONObject().apply {
    put("title", title)
    put("originalTitle", originalTitle)
    put("overview", overview)
    put("year", year)
    put("posterUrl", posterUrl)
    put("backdropUrl", backdropUrl)
    put("rating", rating)
    put("runtimeMinutes", runtimeMinutes ?: JSONObject.NULL)
    put("genres", JSONArray().apply { genres.forEach { put(it) } })
    put("directors", JSONArray().apply {
        directors.forEach { person ->
            put(JSONObject().apply {
                put("name", person.name)
                put("role", person.role)
                put("profileUrl", person.profileUrl)
            })
        }
    })
    put("cast", JSONArray().apply {
        cast.forEach { person ->
            put(JSONObject().apply {
                put("name", person.name)
                put("role", person.role)
                put("profileUrl", person.profileUrl)
            })
        }
    })
    put("seasons", JSONArray().apply {
        seasons.forEach { season ->
            put(JSONObject().apply {
                put("number", season.number)
                put("name", season.name)
                put("overview", season.overview)
                put("posterUrl", season.posterUrl)
                put("episodes", JSONArray().apply {
                    season.episodes.forEach { episode ->
                        put(JSONObject().apply {
                            put("number", episode.number)
                            put("name", episode.name)
                            put("overview", episode.overview)
                            put("stillUrl", episode.stillUrl)
                            put("rating", episode.rating)
                        })
                    }
                })
            })
        }
    })
}

private fun JSONObject.toTmdbTvMetadata(): TmdbTvMetadata = TmdbTvMetadata(
    title = optString("title"),
    originalTitle = optString("originalTitle"),
    overview = optString("overview"),
    year = optString("year"),
    posterUrl = optString("posterUrl"),
    backdropUrl = optString("backdropUrl"),
    rating = optDouble("rating", 0.0),
    genres = optJSONArray("genres").stringValues(),
    runtimeMinutes = optInt("runtimeMinutes", 0).takeIf { it > 0 },
    directors = optJSONArray("directors").objectValues().map { person ->
        TmdbPerson(person.optString("name"), person.optString("role"), person.optString("profileUrl"))
    },
    cast = optJSONArray("cast").objectValues().map { person ->
        TmdbPerson(person.optString("name"), person.optString("role"), person.optString("profileUrl"))
    },
    seasons = optJSONArray("seasons").objectValues().map { season ->
        TmdbSeasonMetadata(
            number = season.optInt("number"),
            name = season.optString("name"),
            overview = season.optString("overview"),
            posterUrl = season.optString("posterUrl"),
            episodes = season.optJSONArray("episodes").objectValues().map { episode ->
                TmdbEpisodeMetadata(
                    number = episode.optInt("number"),
                    name = episode.optString("name"),
                    overview = episode.optString("overview"),
                    stillUrl = episode.optString("stillUrl"),
                    rating = episode.optDouble("rating", 0.0)
                )
            }
        )
    }
)

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add) }
}

private fun JSONArray?.objectValues(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList { for (index in 0 until length()) optJSONObject(index)?.let(::add) }
}
