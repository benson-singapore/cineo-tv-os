package com.nova.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class OrbitAccessGateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCanRetryAndUnlockWithoutLeavingPasswordOnScreen() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "orbit-gate-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("orbit_gate_test_" + name, mode)
        }
        val credentials = DeviceCredentialStore(context)
        val settings = OrbitLocalSettings(context, credentials)
        settings.enabled = true
        assertTrue(settings.setPassword("1234"))
        var unlocked = false
        compose.setContent {
            val menu = remember { FocusRequester() }
            val field = remember { FocusRequester() }
            Box {
                Box(Modifier.testTag("gate-menu").focusRequester(menu).focusable())
                OrbitAccessGate(settings, field, menu) { unlocked = true }
            }
        }
        compose.onNodeWithTag("orbit-gate-password").assertIsFocused().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("wrong")
        compose.onNodeWithTag("orbit-gate-verify").performClick()
        compose.onNodeWithTag("orbit-gate-error").assertIsDisplayed()
        assertFalse(unlocked)
        compose.onNodeWithTag("orbit-gate-password").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("1234")
        compose.onNodeWithTag("orbit-gate-verify").performClick()
        compose.runOnIdle { assertTrue(unlocked) }
        assertTrue(settings.unlocked())
    }
}
