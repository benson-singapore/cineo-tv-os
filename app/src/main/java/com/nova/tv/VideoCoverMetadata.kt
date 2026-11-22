package com.nova.tv

import org.json.JSONObject

/** Only show episode counts supplied by the source, never infer them from titles. */
internal fun videoEpisodeBadge(item: JSONObject): String {
    val remarks = item.optString("vod_remarks").trim()
    val complete = item.optString("vod_isend").trim() == "1"
    if (Regex("\\d+\\s*[集话期]|完结|完結|更新|连载|連載").containsMatchIn(remarks)) {
        return if (complete && !Regex("完结|完結|更新|连载|連載").containsMatchIn(remarks)) "$remarks · 完结" else remarks
    }
    val serial = item.optString("vod_serial").trim().toIntOrNull()
    if (serial != null && serial > 0) return if (complete) "${serial}集 · 完结" else "更新至${serial}集"
    val total = item.optString("vod_total").trim().toIntOrNull()
    if (total != null && total > 0) return if (complete) "共${total}集 · 完结" else "共${total}集"
    return if (complete) "完结" else ""
}

internal fun validCoverRating(value: String): Boolean =
    value.trim().toDoubleOrNull()?.let { it.isFinite() && it > 0.0 && it <= 10.0 } == true
