package com.nova.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrbitPluginPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun showsMetadataAndSwitches() {
        var picked = ""
        val first = OrbitPluginConfig("1", "第一插件", "a", "c", description = "插件说明")
        val second = OrbitPluginConfig("2", "第二插件", "b", "c")
        compose.setContent {
            Column {
                OrbitPluginPickerItem(first, selected = true) { picked = first.id }
                OrbitPluginPickerItem(second, selected = false) { picked = second.id }
            }
        }
        compose.onNodeWithText("插件说明").assertIsDisplayed()
        compose.onNodeWithText("暂无插件说明").assertIsDisplayed()
        compose.onNodeWithText("当前").assertIsDisplayed()
        compose.onNodeWithText("第二插件").performClick()
        compose.runOnIdle { assertEquals("2", picked) }
    }
}
