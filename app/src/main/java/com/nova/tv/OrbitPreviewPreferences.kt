package com.nova.tv

import android.content.SharedPreferences

internal data class OrbitPreviewConfig(val columns: Int = 4, val ratio: String = "3:4")

internal class OrbitPreviewPreferences(private val preferences: SharedPreferences) {
    private val ratios = setOf("3:4", "4:3", "16:9", "9:16", "1:1")

    private fun key(hub: String, pluginId: String, field: String): String =
        "plugin.${hub.length}:$hub${pluginId.length}:$pluginId:$field"

    fun read(hub: String, pluginId: String): OrbitPreviewConfig {
        val columnsKey = key(hub, pluginId, "columns")
        val ratioKey = key(hub, pluginId, "ratio")
        if (!preferences.getBoolean("preview_migrated", false)) {
            val editor = preferences.edit().putBoolean("preview_migrated", true)
            if (!preferences.contains(columnsKey) && preferences.contains("columns")) {
                editor.putInt(columnsKey, preferences.getInt("columns", 4))
            }
            if (!preferences.contains(ratioKey) && preferences.contains("ratio")) {
                editor.putString(ratioKey, preferences.getString("ratio", "3:4"))
            }
            editor.apply()
        }
        return OrbitPreviewConfig(
            preferences.getInt(columnsKey, 4).coerceIn(2, 6),
            preferences.getString(ratioKey, "3:4")?.takeIf { it in ratios } ?: "3:4"
        )
    }

    fun write(hub: String, pluginId: String, config: OrbitPreviewConfig) {
        preferences.edit().putInt(key(hub, pluginId, "columns"), config.columns.coerceIn(2, 6))
            .putString(key(hub, pluginId, "ratio"), config.ratio.takeIf { it in ratios } ?: "3:4").apply()
    }
}
