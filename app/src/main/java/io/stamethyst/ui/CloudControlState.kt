package io.stamethyst.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.CloudControlSettings
import kotlinx.coroutines.channels.Channel

@Composable
fun rememberCloudControlSettings(): State<CloudControlSettings> =
    produceState(initialValue = CloudControlConfig.current()) {
        val updates = Channel<CloudControlSettings>(Channel.CONFLATED)
        val listener = {
            updates.trySend(CloudControlConfig.current())
            Unit
        }

        CloudControlConfig.addListener(listener)
        value = CloudControlConfig.current()
        try {
            for (settings in updates) {
                value = settings
            }
        } finally {
            CloudControlConfig.removeListener(listener)
            updates.close()
        }
    }
