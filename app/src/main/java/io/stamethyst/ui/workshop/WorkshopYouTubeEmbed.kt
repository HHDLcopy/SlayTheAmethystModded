package io.stamethyst.ui.workshop

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView

internal const val WORKSHOP_YOUTUBE_EMBED_BASE_URL = "https://steamcommunity.com"
internal const val WORKSHOP_YOUTUBE_READY_CONSOLE_MESSAGE = "WORKSHOP_YOUTUBE_READY"
internal const val WORKSHOP_YOUTUBE_PLAYING_CONSOLE_MESSAGE = "WORKSHOP_YOUTUBE_PLAYING"
internal const val WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX = "WORKSHOP_YOUTUBE_ERROR:"

internal fun WebView.configureWorkshopYouTubeWebView(
    webChromeClient: WebChromeClient = WebChromeClient(),
) {
    setBackgroundColor(android.graphics.Color.BLACK)
    overScrollMode = WebView.OVER_SCROLL_NEVER
    isHorizontalScrollBarEnabled = false
    isVerticalScrollBarEnabled = false
    settings.javaScriptEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.domStorageEnabled = true
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
    CookieManager.getInstance().setAcceptCookie(true)
    this.webChromeClient = webChromeClient
}

internal fun buildWorkshopYouTubeEmbedHtml(youtubeVideoId: String): String {
    val safeVideoId = youtubeVideoId.takeIf(::isSafeWorkshopYouTubeVideoId).orEmpty()
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
          <style>
            html, body {
              margin: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background: #000000;
            }
            #player {
              position: fixed;
              inset: 0;
              width: 100%;
              height: 100%;
              background: #000000;
            }
          </style>
        </head>
        <body>
          <iframe
            id="player"
            src="https://www.youtube.com/embed/$safeVideoId?enablejsapi=1&autoplay=1&mute=1&playsinline=1&controls=1&fs=1&modestbranding=1&rel=0&origin=https%3A%2F%2Fsteamcommunity.com"
            allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
            allowfullscreen>
          </iframe>
          <script>
            const WORKSHOP_VIDEO_ID = "$safeVideoId";
            let player = null;
            let playAttempts = 0;

            function emit(message) {
              console.log(message);
            }

            function requestPlayback() {
              if (!player || !WORKSHOP_VIDEO_ID) {
                return;
              }
              try {
                player.mute();
                player.playVideo();
              } catch (error) {
                emit("${WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX}play_exception");
              }
              playAttempts += 1;
              if (playAttempts < 8 && player.getPlayerState && player.getPlayerState() !== 1) {
                window.setTimeout(requestPlayback, 500);
              }
            }

            function onYouTubeIframeAPIReady() {
              if (!WORKSHOP_VIDEO_ID) {
                emit("${WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX}invalid_video_id");
                return;
              }
              player = new YT.Player("player", {
                events: {
                  onReady: function(event) {
                    emit("$WORKSHOP_YOUTUBE_READY_CONSOLE_MESSAGE");
                    requestPlayback();
                  },
                  onStateChange: function(event) {
                    if (event.data === 1) {
                      emit("$WORKSHOP_YOUTUBE_PLAYING_CONSOLE_MESSAGE");
                    }
                  },
                  onError: function(event) {
                    emit("${WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX}" + event.data);
                  }
                }
              });
            }
          </script>
          <script src="https://www.youtube.com/iframe_api"></script>
        </body>
        </html>
    """.trimIndent()
}

private fun isSafeWorkshopYouTubeVideoId(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && value.all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '_' ||
            char == '-'
    }
