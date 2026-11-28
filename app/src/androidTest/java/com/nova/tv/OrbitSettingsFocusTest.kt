package com.nova.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class OrbitSettingsFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteNavigatesBetweenMenuAndPasswordControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = DeviceCredentialStore(context)
        val settings = OrbitLocalSettings(context, credentials)
        val repository = OrbitPluginRepository(credentials, settings)
        compose.setContent {
            val menu = remember { FocusRequester() }
            val entry = remember { FocusRequester() }
            Column {
                Box(Modifier.testTag("settings-menu").focusRequester(menu).focusable())
                OrbitSettingsPage(repository, entry, menu) { }
            }
            LaunchedEffect(Unit) { entry.requestFocus() }
        }
        fun down(tag: String) = compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("orbit-global").assertIsFocused()
        down("orbit-global")
        compose.onNodeWithTag("orbit-timeout-1").assertIsFocused()
        compose.onNodeWithTag("orbit-timeout-1").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("orbit-timeout-5").assertIsFocused()
        down("orbit-timeout-5")
        compose.onNodeWithTag("orbit-password-button").assertIsFocused().performClick()
        val first = if (settings.hasPassword) "orbit-old-password" else "orbit-new-password"
        compose.onNodeWithTag(first).assertIsFocused()
        if (settings.hasPassword) down(first)
        compose.onNodeWithTag("orbit-new-password").assertIsFocused()
        down("orbit-new-password")
        compose.onNodeWithTag("orbit-confirm-password").assertIsFocused()
        down("orbit-confirm-password")
        compose.onNodeWithTag("orbit-save-password").assertIsFocused()
        compose.onNodeWithTag("orbit-save-password").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("orbit-password-cancel").assertIsFocused().performClick()
        compose.onNodeWithTag("orbit-password-button").assertIsFocused()
        down("orbit-password-button")
        compose.onNodeWithTag("orbit-manage-plugins").assertIsFocused().performClick()
        compose.onNodeWithTag("orbit-refresh").assertIsFocused()
        compose.onNodeWithTag("orbit-refresh").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("orbit-plugin-close").assertIsFocused().performClick()
        compose.onNodeWithTag("orbit-manage-plugins").assertIsFocused()
        compose.onNodeWithTag("orbit-manage-plugins").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("settings-menu").assertIsFocused()
    }
    @Test fun narrowPageScrollsToEveryTimeoutOption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = DeviceCredentialStore(context)
        val repository = OrbitPluginRepository(credentials, OrbitLocalSettings(context, credentials))
        compose.setContent {
            val menu = remember { FocusRequester() }
            val entry = remember { FocusRequester() }
            Column(Modifier.width(240.dp)) {
                Box(Modifier.testTag("settings-menu").focusRequester(menu).focusable())
                OrbitSettingsPage(repository, entry, menu) { }
            }
            LaunchedEffect(Unit) { entry.requestFocus() }
        }
        compose.onNodeWithTag("orbit-global").performKeyInput { pressKey(Key.DirectionDown) }
        listOf(1, 5, 10, 30).forEach { minutes ->
            compose.onNodeWithTag("orbit-timeout-$minutes").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithTag("orbit-timeout-60").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("orbit-timeout-60").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("orbit-timeout-30").assertIsFocused().assertIsDisplayed()
    }

    @Test fun pluginManagementTogglesSavedPluginAndRestoresFocus() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("orbit_modal_test_" + name, mode)
        }
        val credentials = DeviceCredentialStore(context)
        credentials.save("http://example.test", "device", "test-token")
        val settings = OrbitLocalSettings(context, credentials)
        val plugin = OrbitPluginConfig("modal-test", "测试插件", "test", "channel")
        settings.saveSnapshot(settings.bindingScope(), listOf(plugin), emptyMap())
        val repository = OrbitPluginRepository(credentials, settings)
        var disabledId: String? = null
        compose.setContent {
            val menu = remember { FocusRequester() }
            val entry = remember { FocusRequester() }
            Column {
                Box(Modifier.testTag("settings-menu").focusRequester(menu).focusable())
                OrbitSettingsPage(repository, entry, menu) { disabledId = it }
            }
            LaunchedEffect(Unit) { entry.requestFocus() }
        }
        compose.onNodeWithText("测试插件").assertDoesNotExist()
        compose.onNodeWithTag("orbit-manage-plugins").performClick()
        compose.onNodeWithText("测试插件").assertIsDisplayed()
        compose.onNodeWithTag("orbit-refresh").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("orbit-plugin-0").assertIsFocused().performClick()
        compose.waitUntil(5000) { !settings.isEnabled("modal-test") }
        compose.onNodeWithText("已停用").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals("modal-test", disabledId) }
        compose.onNodeWithTag("orbit-plugin-0").performClick()
        compose.waitUntil(5000) { settings.isEnabled("modal-test") }
        compose.onNodeWithTag("orbit-plugin-0").assertTextContains("停用")
        compose.onNodeWithTag("orbit-plugin-0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("orbit-plugin-close").assertIsFocused().performClick()
        compose.onNodeWithText("测试插件").assertDoesNotExist()
        compose.onNodeWithTag("orbit-manage-plugins").assertIsFocused()
        credentials.clearLocalBinding()
    }

}
