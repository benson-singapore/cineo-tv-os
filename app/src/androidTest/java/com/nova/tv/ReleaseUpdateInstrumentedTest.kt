package com.nova.tv

import android.content.ActivityNotFoundException
import android.app.Instrumentation
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalTestApi::class)
class ReleaseUpdateInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val originalEndpoint = releaseEndpoint
    @After fun restoreEndpoint() { releaseEndpoint = originalEndpoint }

    private fun serve(body: String, received: CountDownLatch): ServerSocket {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            socket.use { server ->
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    received.countDown()
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        return socket
    }

    @Test fun startupRequestsAndReadsRelease() {
        val received = CountDownLatch(1)
        val server = serve("{\"tag_name\":\"v9.9.9\"}", received)
        releaseEndpoint = "http://127.0.0.1:${server.localPort}/latest"
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("startup must request release", received.await(15, TimeUnit.SECONDS))
        }
    }

    @Test fun startupNewReleaseDisplaysGlobalDot() {
        val received = CountDownLatch(1)
        val server = serve("{\"tag_name\":\"v9.9.9\"}", received)
        releaseEndpoint = "http://127.0.0.1:${server.localPort}/latest"
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(received.await(15, TimeUnit.SECONDS))
            compose.waitUntil(15000) { compose.onAllNodesWithTag("settings-update-dot", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-update-dot", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("配置").performClick()
            compose.onNodeWithText("版本更新").performClick()
            compose.onNodeWithText("发现新版本 v9.9.9").assertExists()
            compose.onNodeWithTag("current-version-card").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("current-version-card").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
            compose.onNodeWithText("打开下载页面").assertIsFocused()
        }
    }

    @Test fun releaseResponsesAndNetworkFailure() = runBlocking {
        for ((tag, expected) in listOf("v9.9.9" to true, "v0.1.2" to false)) {
            val received = CountDownLatch(1)
            val server = serve("{\"tag_name\":\"$tag\"}", received)
            releaseEndpoint = "http://127.0.0.1:${server.localPort}/latest"
            assertEquals(expected, checkRelease("0.1.2") != null)
            assertTrue(received.await(2, TimeUnit.SECONDS))
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        releaseEndpoint = "http://127.0.0.1:$closedPort/latest"
        assertTrue(runCatching { checkRelease("0.1.2") }.isFailure)
    }

    @Test fun dotAndDownloadPageAction() {
        var opened = ""
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SettingsMenuItem("版本更新", selected = true, showDot = true) { }
                AppVersionPage("0.1.2", "3", FocusRequester(), ReleaseUpdate("v9.9.9", "https://github.com/benson-singapore/cineo-tv-os/releases/latest"), false, false, { opened = it })
            }
        }
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("发现新版本 v9.9.9").assertExists()
        compose.onNodeWithText("打开下载页面").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("打开下载页面").assertIsFocused()
        compose.onNodeWithText("打开下载页面").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("https://github.com/benson-singapore/cineo-tv-os/releases/latest", opened) }
    }

    @Test fun missingBrowserShowsError() {
        compose.setContent {
            AppVersionPage("0.1.2", "3", FocusRequester(), ReleaseUpdate("v9.9.9", "https://github.com/benson-singapore/cineo-tv-os/releases/latest"), false, false, { throw ActivityNotFoundException() })
        }
        compose.onNodeWithText("打开下载页面").performClick()
        compose.onNodeWithText("无法打开下载页面，请使用浏览器访问 GitHub 发布页").assertExists()
    }

    @Test fun noUpdateAndFailedStateAreDistinct() {
        compose.setContent { AppVersionPage("0.1.2", "3", FocusRequester(), null, false, true) }
        compose.onNodeWithText("检查更新失败，请检查网络后重新打开应用").assertExists()
        compose.onNodeWithText("打开下载页面").assertDoesNotExist()
    }

    @Test fun downloadDispatchesSystemViewIntent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var captured: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                captured = intent
                return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {
                AppVersionPage("0.1.2", "3", FocusRequester(), ReleaseUpdate("v9.9.9", "https://github.com/benson-singapore/cineo-tv-os/releases/latest"), false, false)
            }
            compose.onNodeWithText("打开下载页面").performClick()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_VIEW, captured?.action)
                assertEquals("https://github.com/benson-singapore/cineo-tv-os/releases/latest", captured?.dataString)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun upToDateShowsNoDot() {
        compose.setContent { SettingsMenuItem("版本更新", selected = false, showDot = false) { } }
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertDoesNotExist()
    }
}
