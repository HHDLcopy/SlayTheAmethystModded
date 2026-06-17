package io.stamethyst.backend.feedback

import android.content.Context
import io.getstream.chat.android.client.ChatClient

object StreamChatPreviewInitializer {
    private const val PREVIEW_API_KEY = "slay-the-amethyst-feedback-preview"

    @JvmStatic
    fun initialize(context: Context) {
        if (ChatClient.isInitialized) {
            return
        }
        ChatClient.Builder(PREVIEW_API_KEY, context.applicationContext)
            .disableWarmUp()
            .build()
    }
}
