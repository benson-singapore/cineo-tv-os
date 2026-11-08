package com.nova.tv

import android.content.Context

data class NavigationMenuSettings(
    val order: List<String>,
    val visibleItems: Set<String>
)

class NavigationMenuPreferences(context: Context) {
    private val database = CineoDatabase(context.applicationContext)
    private val legacyPreferences = context.getSharedPreferences("navigation_menu", Context.MODE_PRIVATE)

    init {
        migrateLegacySettings()
    }

    fun load(): NavigationMenuSettings {
        val savedOrder = database.readAppSetting(ORDER_KEY)
            ?.split(ITEM_SEPARATOR)
            ?.filter { it in menuItems }
            ?.distinct()
            .orEmpty()
        val order = savedOrder + menuItems.filterNot { it in savedOrder }
        val hiddenItems = database.readAppSetting(HIDDEN_KEY)
            .orEmpty()
            .split(ITEM_SEPARATOR)
            .filter { it in menuItems && it != HOME_ITEM }
            .toSet()
        val visibleItems = menuItems.filterNot { it in hiddenItems }.toSet() + HOME_ITEM
        return NavigationMenuSettings(order, visibleItems)
    }

    fun save(settings: NavigationMenuSettings) {
        val requestedOrder = settings.order.filter { it in menuItems }.distinct()
        val order = requestedOrder + menuItems.filterNot { it in requestedOrder }
        val hiddenItems = menuItems.filter { it !in settings.visibleItems && it != HOME_ITEM }
        database.writeAppSettings(
            mapOf(
                ORDER_KEY to order.joinToString(ITEM_SEPARATOR),
                HIDDEN_KEY to hiddenItems.joinToString(ITEM_SEPARATOR)
            )
        )
    }

    private fun migrateLegacySettings() {
        if (database.readAppSetting(MIGRATION_KEY) == "done") return
        val values = buildMap {
            legacyPreferences.getString(ORDER_KEY, null)?.let { put(ORDER_KEY, it) }
            legacyPreferences.getString(HIDDEN_KEY, null)?.let { put(HIDDEN_KEY, it) }
            put(MIGRATION_KEY, "done")
        }
        database.writeAppSettings(values)
    }

    companion object {
        const val HOME_ITEM = "主页"
        val menuItems = listOf(
            HOME_ITEM,
            "电影",
            "剧集",
            "动漫",
            "短剧",
            "纪录片",
            "继续观看",
            "收藏",
            "搜索"
        )

        private const val ORDER_KEY = "order"
        private const val HIDDEN_KEY = "hidden"
        private const val MIGRATION_KEY = "migration.navigation_menu.v1"
        private const val ITEM_SEPARATOR = "|"
    }
}
