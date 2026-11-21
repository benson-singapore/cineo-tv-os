package com.nova.tv

import org.json.JSONObject

/** Only show episode counts supplied by the source, never infer them from titles. */
internal fun videoEpisodeBadge(item: JSONObject): String {
    val remarks = item.optString("vod_remarks").trim()
    if (Regex("\\d+\\s*[集话期]").containsMatchIn(remarks)) return remarks
    val serial = item.optString("vod_serial").trim().toIntOrNull()
    if (serial != null && serial > 0) return "更新至${serial}集"
    val total = item.optString("vod_total").trim().toIntOrNull()
    if (total != null && total > 0) return "共${total}集"
    return ""
}

internal fun validCoverRating(value: String): Boolean =
    value.trim().toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true
