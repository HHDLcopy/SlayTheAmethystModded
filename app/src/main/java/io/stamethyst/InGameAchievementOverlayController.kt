package io.stamethyst

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import io.stamethyst.config.LauncherConfig
import io.stamethyst.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/** Non-interactive launcher-owned overlay for achievement events emitted by the game JVM. */
internal class InGameAchievementOverlayController(
    private val activity: StsGameActivity,
) {
    private val queuedApiNames = mutableStateListOf<String>()
    private val displayedApiNames = mutableSetOf<String>()
    private var activeApiName by mutableStateOf<String?>(null)
    private var composeView: ComposeView? = null

    fun attachToHost(host: FrameLayout) {
        if (composeView?.parent === host) return
        detachView()
        val themeMode = LauncherConfig.readThemeMode(activity)
        val themeColor = LauncherConfig.readThemeColor(activity)
        composeView = ComposeView(activity).apply {
            visibility = View.VISIBLE
            isClickable = false
            isFocusable = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LauncherTheme(themeMode = themeMode, themeColor = themeColor) {
                    AchievementNotificationHost(
                        activeApiName = activeApiName,
                        onDisplayed = ::showNext,
                    )
                }
            }
        }.also { view ->
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun enqueue(apiNames: Collection<String>) {
        apiNames.forEach { apiName ->
            if (apiName in SteamAchievementCatalog.apiNames && displayedApiNames.add(apiName)) {
                queuedApiNames += apiName
            }
        }
        showNext()
    }

    fun onDestroy() {
        queuedApiNames.clear()
        displayedApiNames.clear()
        activeApiName = null
        detachView()
    }

    private fun showNext() {
        if (activeApiName != null || queuedApiNames.isEmpty()) return
        activeApiName = queuedApiNames.removeAt(0)
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS)
                release()
            }
        }
    }

    private fun dismissActive() {
        activeApiName = null
        showNext()
    }

    private fun detachView() {
        val view = composeView ?: return
        composeView = null
        view.disposeComposition()
        (view.parent as? FrameLayout)?.removeView(view)
    }

    @Composable
    private fun AchievementNotificationHost(
        activeApiName: String?,
        onDisplayed: () -> Unit,
    ) {
        val entry = SteamAchievementCatalog.entries.firstOrNull { it.apiName == activeApiName }
        LaunchedEffect(activeApiName) {
            if (activeApiName == null) return@LaunchedEffect
            delay(DISPLAY_DURATION_MS)
            dismissActive()
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd,
        ) {
            AnimatedVisibility(
                visible = entry != null,
                enter = fadeIn(tween(ENTER_DURATION_MS)) + slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(ENTER_DURATION_MS),
                ),
                exit = fadeOut(tween(EXIT_DURATION_MS)) + slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(EXIT_DURATION_MS),
                ),
                modifier = Modifier.padding(PaddingValues(end = 20.dp, bottom = 26.dp)),
            ) {
                entry?.let { SteamAchievementNotification(it) }
            }
        }
    }

    @Composable
    private fun SteamAchievementNotification(entry: SteamAchievementCatalog.Entry) {
        Row(
            modifier = Modifier
                .width(338.dp)
                .shadow(14.dp, RoundedCornerShape(4.dp), clip = false)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF17212B))
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(entry.unlockedIconResId),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF0D141C)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(11.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.in_game_achievement_unlocked),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8FCAE8),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                androidx.compose.material3.Text(
                    text = stringResource(entry.titleResId),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                androidx.compose.material3.Text(
                    text = stringResource(entry.descriptionResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8C7D1),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    private companion object {
        const val DISPLAY_DURATION_MS = 4_000L
        const val ENTER_DURATION_MS = 220
        const val EXIT_DURATION_MS = 260
        const val TONE_DURATION_MS = 120
        const val TONE_VOLUME = 70
    }
}
