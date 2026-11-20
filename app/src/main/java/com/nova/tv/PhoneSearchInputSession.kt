package com.nova.tv

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A focused search field owns one short-lived session. Local edits take priority. */
@Composable
internal fun PhoneSearchInputSession(focused: Boolean, query: String, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val currentQuery by rememberUpdatedState(query)
    val currentOnText by rememberUpdatedState(onText)
    LaunchedEffect(focused, resumed) {
        if (!focused || !resumed) return@LaunchedEffect
        val credentials = DeviceCredentialStore(context)
        if (!credentials.isBound) return@LaunchedEffect
        val api = HubSyncApi(credentials)
        val initialQuery = currentQuery
        var id: String? = null
        try {
            val created = withContext(NonCancellable + Dispatchers.IO) { api.createInputSession() }
            if (!created.envelope.successful) return@LaunchedEffect
            id = created.envelope.data?.optString("id")?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            val sessionId = id
            while (true) {
                delay(2500)
                val reply = withContext(Dispatchers.IO) { api.inputSession(sessionId) }
                if (reply.status == 404 || reply.status == 403) break
                if (!reply.envelope.successful) continue
                val data = reply.envelope.data ?: continue
                if (data.optString("status") == "submitted") {
                    if (currentQuery == initialQuery) currentOnText(data.optString("text"))
                    break
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Search remains usable when Hub is unavailable.
        } finally {
            id?.let { sessionId ->
                withContext(NonCancellable + Dispatchers.IO) { runCatching { api.closeInputSession(sessionId) } }
            }
        }
    }
}
