package io.stamethyst

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.backend.easytier.EasyTierPermissionCoordinator
import io.stamethyst.config.LauncherConfig
import io.stamethyst.ui.main.EasyTierBottomSheetContent
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

internal class InGameEasyTierOverlayController(
    private val activity: StsGameActivity,
    private val viewModel: MainScreenViewModel,
) {
    private var overlayVisible by mutableStateOf(false)
    private var composeView: ComposeView? = null

    fun attachToHost(host: FrameLayout) {
        if (composeView?.parent === host) {
            return
        }
        detachView()
        val themeMode = LauncherConfig.readThemeMode(activity)
        val themeColor = LauncherConfig.readThemeColor(activity)
        composeView = ComposeView(activity).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LauncherTheme(
                    themeMode = themeMode,
                    themeColor = themeColor,
                ) {
                    InGameEasyTierDialogHost(
                        visible = overlayVisible,
                        activity = activity,
                        viewModel = viewModel,
                        onDismiss = ::dismiss,
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

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        viewModel.syncEasyTierUi(activity)
        composeView?.visibility = View.VISIBLE
        overlayVisible = true
    }

    fun dismiss() {
        overlayVisible = false
        composeView?.postDelayed(
            {
                if (!overlayVisible) {
                    composeView?.visibility = View.GONE
                }
            },
            DISMISS_VIEW_DELAY_MS,
        )
    }

    fun onDestroy() {
        overlayVisible = false
        detachView()
    }

    private fun detachView() {
        val view = composeView ?: return
        composeView = null
        view.disposeComposition()
        (view.parent as? FrameLayout)?.removeView(view)
    }

    private companion object {
        const val DISMISS_VIEW_DELAY_MS = 80L
    }
}

@Composable
private fun InGameEasyTierDialogHost(
    visible: Boolean,
    activity: StsGameActivity,
    viewModel: MainScreenViewModel,
    onDismiss: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onEasyTierVpnPermissionResult(
            host = activity,
            granted = result.resultCode == Activity.RESULT_OK,
        )
    }

    if (!visible) {
        return
    }

    val uiState = viewModel.uiState
    LaunchedEffect(Unit) {
        viewModel.syncEasyTierUi(activity)
        viewModel.refreshEasyTierRooms(activity, forceRoomInfoReload = true)
        while (true) {
            delay(EASY_TIER_ROOM_AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshEasyTierRooms(
                activity,
                forceRoomInfoReload = true,
                showLoading = false,
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 760.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            EasyTierBottomSheetContent(
                indicator = uiState.easyTierIndicator,
                roomBrowser = uiState.easyTierRoomBrowser,
                onRefreshRooms = {
                    viewModel.refreshEasyTierRooms(activity, forceRoomInfoReload = true)
                },
                onSelectRoom = { roomId ->
                    viewModel.selectEasyTierRoom(activity, roomId)
                },
                onCreateRoom = { roomId, description, allowNewJoins ->
                    val permissionIntent =
                        EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.queueEasyTierRoomCreation(roomId, description, allowNewJoins)
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        permissionLauncher.launch(permissionIntent)
                    } else {
                        viewModel.createEasyTierRoom(activity, roomId, description, allowNewJoins)
                    }
                },
                onLockRoom = { viewModel.lockEasyTierRoom(activity) },
                onUnlockRoom = { viewModel.unlockEasyTierRoom(activity) },
                onCloseRoom = { viewModel.closeEasyTierRoom(activity) },
                onConnect = {
                    val permissionIntent =
                        EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        permissionLauncher.launch(permissionIntent)
                    } else {
                        viewModel.onConnectEasyTier(activity)
                    }
                },
                onDisconnect = { viewModel.onDisconnectEasyTier(activity) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val EASY_TIER_ROOM_AUTO_REFRESH_INTERVAL_MS = 5_000L
