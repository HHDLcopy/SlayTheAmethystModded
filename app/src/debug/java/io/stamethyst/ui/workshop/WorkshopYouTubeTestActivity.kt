package io.stamethyst.ui.workshop

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.io.File

class WorkshopYouTubeTestActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observedMessages = ArrayList<String>()
    private var webView: WebView? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        WorkshopYouTubePlaybackTestHost.resultFile(this).delete()
        writeResult(WorkshopYouTubePlaybackTestHost.STATUS_STARTED)

        val youtubeVideoId = intent.getStringExtra(WorkshopYouTubePlaybackTestHost.EXTRA_VIDEO_ID)
            ?: WorkshopYouTubePlaybackTestHost.DEFAULT_VIDEO_ID
        webView = WebView(this).apply {
            configureWorkshopYouTubeWebView(
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        onPlayerConsoleMessage(consoleMessage.message())
                        return true
                    }
                },
            )
        }
        setContentView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView?.loadDataWithBaseURL(
            WORKSHOP_YOUTUBE_EMBED_BASE_URL,
            buildWorkshopYouTubeEmbedHtml(youtubeVideoId),
            "text/html",
            "utf-8",
            null,
        )

        mainHandler.postDelayed(
            {
                complete(
                    WorkshopYouTubePlaybackTestHost.STATUS_TIMEOUT,
                    "YouTube player did not reach PLAYING state",
                )
            },
            WorkshopYouTubePlaybackTestHost.PLAYBACK_TIMEOUT_MS,
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.run {
            onPause()
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun onPlayerConsoleMessage(message: String) {
        observedMessages += message
        when {
            message == WORKSHOP_YOUTUBE_PLAYING_CONSOLE_MESSAGE -> {
                complete(WorkshopYouTubePlaybackTestHost.STATUS_PLAYING, "player reached PLAYING")
            }

            message.startsWith(WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX) -> {
                complete(WorkshopYouTubePlaybackTestHost.STATUS_ERROR, message)
            }
        }
    }

    private fun complete(status: String, detail: String) {
        if (completed) {
            return
        }
        completed = true
        writeResult(status, detail)
        finish()
    }

    private fun writeResult(status: String, detail: String = "") {
        WorkshopYouTubePlaybackTestHost.resultFile(this).writeText(
            buildString {
                append("status=").append(status).append('\n')
                append("detail=").append(detail).append('\n')
                append("observed=").append(observedMessages.joinToString("|"))
            },
        )
    }
}

object WorkshopYouTubePlaybackTestHost {
    const val EXTRA_VIDEO_ID = "youtubeVideoId"
    const val DEFAULT_VIDEO_ID = "vYthsh8a1Dc"
    const val STATUS_STARTED = "started"
    const val STATUS_PLAYING = "playing"
    const val STATUS_ERROR = "error"
    const val STATUS_TIMEOUT = "timeout"
    const val PLAYBACK_TIMEOUT_MS = 45_000L

    fun resultFile(context: Context): File =
        File(context.filesDir, "workshop_youtube_playback_test_result.txt")
}
