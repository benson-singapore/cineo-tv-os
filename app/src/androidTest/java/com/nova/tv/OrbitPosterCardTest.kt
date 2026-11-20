package com.nova.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class OrbitPosterCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun yellowPeachMetadataAppearsOnCard() {
        compose.setContent {
            OrbitPosterCard("示例", "", "短剧", metadata = orbitCoverMetadata("aihuangdou", "评分 8.6 分 · 更新中 · 6集 · 简介")) { }
        }
        compose.onNodeWithText("8.6 分").assertIsDisplayed()
        compose.onNodeWithText("6 集").assertIsDisplayed()
    }

    @Test fun otherPluginDoesNotShowBadges() {
        compose.setContent {
            OrbitPosterCard("示例", "", "短剧", metadata = orbitCoverMetadata("other", "评分 8.6 分 · 6集")) { }
        }
        compose.onNodeWithText("8.6 分").assertDoesNotExist()
        compose.onNodeWithText("6 集").assertDoesNotExist()
    }
}
