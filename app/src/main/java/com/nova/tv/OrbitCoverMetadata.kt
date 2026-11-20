package com.nova.tv

internal data class OrbitCoverMetadata(val rating: String?, val episodes: String?)

/** Only the metadata prefix is interpreted; the synopsis is never searched for numbers. */
internal fun orbitCoverMetadata(pluginId: String?, content: String?): OrbitCoverMetadata {
    if (pluginId != "aihuangdou" || content.isNullOrBlank()) return OrbitCoverMetadata(null, null)
    val ratingPattern = Regex("评分\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s*分")
    val episodePattern = Regex("(\\d{1,6})\\s*集")
    var rating: String? = null
    var episodes: String? = null
    for (segment in content.split('·').map { it.trim() }) {
        val score = ratingPattern.matchEntire(segment)
        val count = episodePattern.matchEntire(segment)
        when {
            score != null -> {
                val value = score.groupValues[1]
                if (rating == null && value.toDoubleOrNull()?.let { it in 0.0..10.0 } == true) rating = "$value 分"
            }
            count != null -> {
                val value = count.groupValues[1]
                if (episodes == null && value.toIntOrNull()?.let { it > 0 } == true) episodes = "${value.toInt()} 集"
            }
            segment in setOf("更新中", "已完结", "完结") -> Unit
            segment.matches(Regex("评分\\s*[^\\s·]{1,8}(?:\\s*分)?")) -> Unit
            else -> break
        }
    }
    return OrbitCoverMetadata(rating, episodes)
}
