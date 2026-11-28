package com.nova.tv

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@UnstableApi
class PlayerRemoteControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenControllerHandlesConfirmUpAndDownOnFirstPress() {
        lateinit var view: EpisodeAwarePlayerView
        lateinit var player: ExoPlayer
        var panelOpens = 0
        compose.setContent {
            AndroidView(factory = { context ->
                player = ExoPlayer.Builder(context).build()
                EpisodeAwarePlayerView(context).also {
                    view = it; it.controllerAutoShow = false; it.player = player
                    it.onDownPressed = { panelOpens++; true }
                    it.hideController()
                }
            })
            DisposableEffect(Unit) { onDispose { view.player = null; player.release() } }
        }
        fun press(code: Int) {
            assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
        }
        compose.runOnIdle {
            player.playWhenReady = true
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertFalse(player.playWhenReady)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(player.playWhenReady)
            press(KeyEvent.KEYCODE_DPAD_UP)
        }
        compose.waitUntil(5000) { view.isControllerFullyVisible }
        compose.runOnIdle {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(1, panelOpens)
        }
    }

    @Test fun visibleEpisodeButtonsInvokeCorrectCallbacks() {
        lateinit var view: EpisodeAwarePlayerView
        lateinit var player: ExoPlayer
        var previous = 0
        var next = 0
        compose.setContent {
            AndroidView(factory = { context ->
                player = ExoPlayer.Builder(context).build()
                EpisodeAwarePlayerView(context).also {
                    view = it; it.controllerAutoShow = false; it.player = player
                    it.previousEnabled = true; it.nextEnabled = true
                    it.onPreviousPressed = { previous++ }; it.onNextPressed = { next++ }
                    it.showController(); it.configureEpisodeButtons()
                }
            })
            DisposableEffect(Unit) { onDispose { view.player = null; player.release() } }
        }
        compose.waitUntil(5000) { view.isControllerFullyVisible }
        compose.runOnIdle {
            view.configureEpisodeButtons()
            val prev = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev)
            val following = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next)
            assertNotNull(prev); assertNotNull(following)
            assertTrue(prev.isShown); assertTrue(following.isShown)
            assertTrue(prev.performClick()); assertTrue(following.performClick())
            assertEquals(1, previous); assertEquals(1, next)
        }
    }
}
