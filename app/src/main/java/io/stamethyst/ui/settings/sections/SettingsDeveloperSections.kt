package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.importing.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.ui.preferences.LauncherPreferences
import kotlin.math.roundToInt
import kotlinx.coroutines.delay


internal data class DeveloperRuntimeSettingsActions(
    val onManualDismissBootOverlayChanged: (Boolean) -> Unit,
    val onSustainedPerformanceModeChanged: (Boolean) -> Unit,
    val onCompendiumUpgradeTouchFixEnabledChanged: (Boolean) -> Unit,
    val onOpenCloudControlConfig: () -> Unit,
    val onDismissCloudControlConfigDialog: () -> Unit,
)


internal data class AdvancedRenderSettingsActions(
    val onRendererSelectionModeChanged: (RendererSelectionMode) -> Unit,
    val onManualRendererBackendChanged: (RendererBackend) -> Unit,
    val onOpenMobileGluesSettings: () -> Unit,
    val onRenderSurfaceBackendChanged: (RenderSurfaceBackend) -> Unit,
    val onGpuResourceGuardianModeChanged: (GpuResourceGuardianMode) -> Unit,
    val onGpuResourceGuardianPressureDownscaleChanged: (Boolean) -> Unit,
    val onJvmHeapMaxSelected: (Int) -> Unit,
    val onJvmCompressedPointersChanged: (Boolean) -> Unit,
    val onJvmStringDeduplicationChanged: (Boolean) -> Unit,
)


internal data class StatusSettingsActions(
    val onLwjglDebugChanged: (Boolean) -> Unit,
    val onPreloadAllJreLibrariesChanged: (Boolean) -> Unit,
    val onLogcatCaptureChanged: (Boolean) -> Unit,
    val onLauncherLogcatCaptureChanged: (Boolean) -> Unit,
    val onJvmLogcatMirrorChanged: (Boolean) -> Unit,
    val onGpuResourceDiagChanged: (Boolean) -> Unit,
    val onGdxPadCursorDebugChanged: (Boolean) -> Unit,
    val onGlBridgeSwapHeartbeatDebugChanged: (Boolean) -> Unit,
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherDeveloperSettingsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onManualDismissBootOverlayChanged: (Boolean) -> Unit = {},
    onSustainedPerformanceModeChanged: (Boolean) -> Unit = {},
    onCompendiumUpgradeTouchFixEnabledChanged: (Boolean) -> Unit = {},
    onSaveSteamCloudPhase0Credentials: (String, String, String) -> Boolean = { _, _, _ -> false },
    onRunSteamCloudPhase0Probe: () -> Unit = {},
    onClearSteamCloudPhase0Credentials: () -> Unit = {},
    onRendererSelectionModeChanged: (RendererSelectionMode) -> Unit = {},
    onManualRendererBackendChanged: (RendererBackend) -> Unit = {},
    onOpenMobileGluesSettings: () -> Unit = {},
    onRenderSurfaceBackendChanged: (RenderSurfaceBackend) -> Unit = {},
    onGpuResourceGuardianModeChanged: (GpuResourceGuardianMode) -> Unit = {},
    onGpuResourceGuardianPressureDownscaleChanged: (Boolean) -> Unit = {},
    onJvmHeapMaxSelected: (Int) -> Unit = {},
    onJvmCompressedPointersChanged: (Boolean) -> Unit = {},
    onJvmStringDeduplicationChanged: (Boolean) -> Unit = {},
    onOpenCompatibility: () -> Unit = {},
    onLwjglDebugChanged: (Boolean) -> Unit = {},
    onPreloadAllJreLibrariesChanged: (Boolean) -> Unit = {},
    onLogcatCaptureChanged: (Boolean) -> Unit = {},
    onLauncherLogcatCaptureChanged: (Boolean) -> Unit = {},
    onJvmLogcatMirrorChanged: (Boolean) -> Unit = {},
    onGpuResourceDiagChanged: (Boolean) -> Unit = {},
    onGdxPadCursorDebugChanged: (Boolean) -> Unit = {},
    onGlBridgeSwapHeartbeatDebugChanged: (Boolean) -> Unit = {},
    onOpenCloudControlConfig: () -> Unit = {},
    onDismissCloudControlConfigDialog: () -> Unit = {},
    onResetLauncherSettingsToDefaults: () -> Unit = {},
) {
    val context = LocalContext.current
    var showWarningDialog by rememberSaveable {
        mutableStateOf(!LauncherPreferences.isDeveloperSettingsWarningDismissed(context))
    }
    var warningRemainingSeconds by rememberSaveable { mutableIntStateOf(5) }

    LaunchedEffect(showWarningDialog) {
        if (showWarningDialog) {
            warningRemainingSeconds = 5
            while (warningRemainingSeconds > 0) {
                delay(1000L)
                warningRemainingSeconds -= 1
            }
        }
    }

    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsDeveloperRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_developer_runtime_title)) {
                SettingsDeveloperRuntimeSection(
                    uiState = uiState,
                    actions = DeveloperRuntimeSettingsActions(
                        onManualDismissBootOverlayChanged = onManualDismissBootOverlayChanged,
                        onSustainedPerformanceModeChanged = onSustainedPerformanceModeChanged,
                        onCompendiumUpgradeTouchFixEnabledChanged =
                            onCompendiumUpgradeTouchFixEnabledChanged,
                        onOpenCloudControlConfig = onOpenCloudControlConfig,
                        onDismissCloudControlConfigDialog = onDismissCloudControlConfigDialog,
                    ),
                )
            }
        }

        item {
            SettingsSectionCard(title = stringResource(R.string.settings_steam_cloud_phase0_title)) {
                SettingsSteamCloudPhase0Section(
                    uiState = uiState,
                    onSaveCredentials = onSaveSteamCloudPhase0Credentials,
                    onRunProbe = onRunSteamCloudPhase0Probe,
                    onClearCredentials = onClearSteamCloudPhase0Credentials,
                )
            }
        }

        item {
            SettingsSectionCard(title = stringResource(R.string.settings_developer_render_title)) {
                SettingsAdvancedRenderSection(
                    uiState = uiState,
                    actions = AdvancedRenderSettingsActions(
                        onRendererSelectionModeChanged = onRendererSelectionModeChanged,
                        onManualRendererBackendChanged = onManualRendererBackendChanged,
                        onOpenMobileGluesSettings = onOpenMobileGluesSettings,
                        onRenderSurfaceBackendChanged = onRenderSurfaceBackendChanged,
                        onGpuResourceGuardianModeChanged = onGpuResourceGuardianModeChanged,
                        onGpuResourceGuardianPressureDownscaleChanged =
                            onGpuResourceGuardianPressureDownscaleChanged,
                        onJvmHeapMaxSelected = onJvmHeapMaxSelected,
                        onJvmCompressedPointersChanged = onJvmCompressedPointersChanged,
                        onJvmStringDeduplicationChanged = onJvmStringDeduplicationChanged,
                    ),
                )
            }
        }

        item {
            SettingsSectionCard(title = stringResource(R.string.compat_settings_title)) {
                SettingsCompatibilitySection(
                    busy = uiState.busy,
                    onOpenCompatibility = onOpenCompatibility,
                )
            }
        }

        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_status_logs)) {
                SettingsStatusSection(
                    uiState = uiState,
                    actions = StatusSettingsActions(
                        onLwjglDebugChanged = onLwjglDebugChanged,
                        onPreloadAllJreLibrariesChanged = onPreloadAllJreLibrariesChanged,
                        onLogcatCaptureChanged = onLogcatCaptureChanged,
                        onLauncherLogcatCaptureChanged = onLauncherLogcatCaptureChanged,
                        onJvmLogcatMirrorChanged = onJvmLogcatMirrorChanged,
                        onGpuResourceDiagChanged = onGpuResourceDiagChanged,
                        onGdxPadCursorDebugChanged = onGdxPadCursorDebugChanged,
                        onGlBridgeSwapHeartbeatDebugChanged = onGlBridgeSwapHeartbeatDebugChanged,
                    ),
                )
            }
        }

        item {
            SettingsSectionCard(title = stringResource(R.string.settings_reset_defaults_section_title)) {
                SettingsResetDefaultsSection(
                    busy = uiState.busy,
                    onResetLauncherSettingsToDefaults = onResetLauncherSettingsToDefaults
                )
            }
        }
    }

    if (showWarningDialog) {
        DeveloperSettingsWarningDialog(
            remainingSeconds = warningRemainingSeconds,
            onDismissPermanently = {
                LauncherPreferences.setDeveloperSettingsWarningDismissed(context, true)
                showWarningDialog = false
            },
            onConfirm = {
                showWarningDialog = false
            },
        )
    }
}


@Composable
internal fun DeveloperSettingsWarningDialog(
    remainingSeconds: Int,
    onDismissPermanently: () -> Unit,
    onConfirm: () -> Unit,
) {
    val actionsEnabled = remainingSeconds <= 0

    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = stringResource(R.string.settings_developer_warning_title)) },
        text = { Text(text = stringResource(R.string.settings_developer_warning_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = actionsEnabled,
            ) {
                Text(text = stringResource(R.string.settings_developer_warning_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissPermanently,
                enabled = actionsEnabled,
            ) {
                Text(text = stringResource(R.string.settings_developer_warning_dont_remind))
            }
        },
    )
}


@Composable
internal fun SettingsResetDefaultsSection(
    busy: Boolean,
    onResetLauncherSettingsToDefaults: () -> Unit,
) {
    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }

    SettingsActionListItem(
        title = stringResource(R.string.settings_reset_defaults_title),
        supportingText = stringResource(R.string.settings_reset_defaults_summary),
        enabled = !busy,
        onClick = { showConfirmDialog = true }
    )

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_reset_defaults_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_reset_defaults_confirm_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                HapticTextButton(
                    enabled = !busy,
                    onClick = {
                        showConfirmDialog = false
                        onResetLauncherSettingsToDefaults()
                    }
                ) {
                    Text(stringResource(R.string.settings_reset_defaults_confirm_action))
                }
            },
            dismissButton = {
                HapticTextButton(
                    enabled = !busy,
                    onClick = { showConfirmDialog = false }
                ) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}


@Composable
internal fun SettingsSteamCloudPhase0Section(
    uiState: SettingsScreenViewModel.UiState,
    onSaveCredentials: (String, String, String) -> Boolean,
    onRunProbe: () -> Unit,
    onClearCredentials: () -> Unit,
) {
    var showCredentialsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingAccountName by rememberSaveable { mutableStateOf("") }
    var pendingRefreshToken by rememberSaveable { mutableStateOf("") }
    var pendingProxyUrl by rememberSaveable { mutableStateOf("") }

    Text(
        text = uiState.steamCloudPhase0StatusText.ifBlank {
            stringResource(R.string.settings_steam_cloud_phase0_status_idle)
        },
        style = MaterialTheme.typography.bodySmall
    )

    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_cloud_phase0_credentials_title),
        supportingText = uiState.steamCloudPhase0CredentialsSummary,
        enabled = !uiState.busy,
        onClick = {
            pendingAccountName = uiState.steamCloudPhase0AccountName
            pendingRefreshToken = ""
            pendingProxyUrl = uiState.steamCloudPhase0ProxyUrl
            showCredentialsDialog = true
        }
    )

    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_cloud_phase0_run_title),
        supportingText = stringResource(R.string.settings_steam_cloud_phase0_run_desc),
        enabled = !uiState.busy,
        onClick = onRunProbe
    )

    SettingsActionListItem(
        title = stringResource(R.string.settings_steam_cloud_phase0_clear_title),
        supportingText = stringResource(R.string.settings_steam_cloud_phase0_clear_desc),
        enabled = !uiState.busy,
        onClick = onClearCredentials
    )

    if (showCredentialsDialog) {
        AlertDialog(
            onDismissRequest = { showCredentialsDialog = false },
            title = { Text(stringResource(R.string.settings_steam_cloud_phase0_credentials_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pendingAccountName,
                        onValueChange = { pendingAccountName = it },
                        singleLine = true,
                        enabled = !uiState.busy,
                        label = { Text(stringResource(R.string.settings_steam_cloud_phase0_account_label)) }
                    )
                    OutlinedTextField(
                        value = pendingRefreshToken,
                        onValueChange = { pendingRefreshToken = it },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        enabled = !uiState.busy,
                        label = { Text(stringResource(R.string.settings_steam_cloud_phase0_token_label)) }
                    )
                    OutlinedTextField(
                        value = pendingProxyUrl,
                        onValueChange = { pendingProxyUrl = it },
                        singleLine = true,
                        enabled = !uiState.busy,
                        label = { Text(stringResource(R.string.settings_steam_cloud_phase0_proxy_label)) }
                    )
                    Text(
                        text = if (uiState.steamCloudPhase0RefreshTokenConfigured) {
                            stringResource(R.string.settings_steam_cloud_phase0_credentials_dialog_keep_token)
                        } else {
                            stringResource(R.string.settings_steam_cloud_phase0_credentials_dialog_new_token)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.settings_steam_cloud_phase0_credentials_dialog_proxy_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        if (onSaveCredentials(pendingAccountName, pendingRefreshToken, pendingProxyUrl)) {
                            showCredentialsDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showCredentialsDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}


@Composable
internal fun SettingsDeveloperEntrySection(
    busy: Boolean,
    onOpenDeveloperSettings: () -> Unit,
) {
    SettingsActionListItem(
        title = stringResource(R.string.settings_developer_open),
        supportingText = stringResource(R.string.settings_developer_summary),
        enabled = !busy,
        onClick = onOpenDeveloperSettings
    )
}


@Composable
internal fun SettingsDeveloperRuntimeSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: DeveloperRuntimeSettingsActions,
) {
    var showGameModeDialog by rememberSaveable { mutableStateOf(false) }

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.sustainedPerformanceModeEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_sustained_performance_enabled),
            disabledText = stringResource(R.string.settings_sustained_performance_disabled),
            description = stringResource(R.string.settings_sustained_performance_desc),
            onCheckedChange = actions.onSustainedPerformanceModeChanged
        )
    )

    SettingsActionListItem(
        title = stringResource(R.string.settings_system_game_mode_title),
        supportingText = stringResource(
            R.string.settings_system_game_mode_summary,
            uiState.systemGameModeDisplayName
        ),
        enabled = !uiState.busy,
        onClick = { showGameModeDialog = true }
    )
    Text(
        text = uiState.systemGameModeDescription,
        style = MaterialTheme.typography.bodySmall
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.manualDismissBootOverlay,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_boot_overlay_manual_enabled),
            disabledText = stringResource(R.string.settings_boot_overlay_manual_disabled),
            description = stringResource(R.string.settings_boot_overlay_manual_desc),
            onCheckedChange = actions.onManualDismissBootOverlayChanged
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.compendiumUpgradeTouchFixEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_compendium_upgrade_touch_fix_enabled),
            disabledText = stringResource(R.string.settings_compendium_upgrade_touch_fix_disabled),
            description = stringResource(R.string.settings_compendium_upgrade_touch_fix_desc),
            onCheckedChange = actions.onCompendiumUpgradeTouchFixEnabledChanged
        )
    )

    if (uiState.cloudControlConfigLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    SettingsActionListItem(
        title = stringResource(
            if (uiState.cloudControlConfigLoading) {
                R.string.settings_developer_cloud_control_loading
            } else {
                R.string.settings_developer_cloud_control_title
            }
        ),
        supportingText = stringResource(R.string.settings_developer_cloud_control_desc),
        enabled = !uiState.busy && !uiState.cloudControlConfigLoading,
        onClick = actions.onOpenCloudControlConfig
    )

    if (showGameModeDialog) {
        AlertDialog(
            onDismissRequest = { showGameModeDialog = false },
            title = { Text(stringResource(R.string.settings_system_game_mode_title)) },
            text = {
                Text(
                    text = listOf(
                        stringResource(
                            R.string.settings_system_game_mode_dialog_current,
                            uiState.systemGameModeDisplayName
                        ),
                        uiState.systemGameModeDescription,
                        stringResource(R.string.settings_system_game_mode_dialog_control),
                        stringResource(R.string.settings_system_game_mode_dialog_panel),
                        stringResource(R.string.settings_system_game_mode_dialog_support)
                    ).joinToString("\n\n")
                )
            },
            confirmButton = {
                TextButton(onClick = { showGameModeDialog = false }) {
                    Text(stringResource(R.string.settings_system_game_mode_acknowledge))
                }
            }
        )
    }

    uiState.cloudControlConfigDialogState?.let { dialogState ->
        AlertDialog(
            onDismissRequest = actions.onDismissCloudControlConfigDialog,
            title = { Text(stringResource(R.string.settings_developer_cloud_control_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_developer_cloud_control_dialog_source,
                            dialogState.sourceDisplayName
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.settings_developer_cloud_control_dialog_url,
                                dialogState.requestUrl
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider()
                    SelectionContainer {
                        Text(
                            text = dialogState.rawText.ifBlank {
                                stringResource(R.string.settings_developer_cloud_control_empty)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = actions.onDismissCloudControlConfigDialog) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

}


@Composable
internal fun SettingsAdvancedRenderSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: AdvancedRenderSettingsActions,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var heapSliderValue by remember(uiState.selectedJvmHeapMaxMb) {
        mutableFloatStateOf(uiState.selectedJvmHeapMaxMb.toFloat())
    }
    var lastHeapStep by remember(
        uiState.selectedJvmHeapMaxMb,
        uiState.jvmHeapMinMb,
        uiState.jvmHeapStepMb,
    ) {
        mutableIntStateOf(
            heapSliderToStep(
                value = uiState.selectedJvmHeapMaxMb.toFloat(),
                min = uiState.jvmHeapMinMb,
                step = uiState.jvmHeapStepMb,
            )
        )
    }

    Text(
        text = stringResource(R.string.settings_renderer_backend_title),
        style = MaterialTheme.typography.bodyMedium
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.rendererSelectionMode == RendererSelectionMode.AUTO,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_renderer_auto_enabled),
            disabledText = stringResource(R.string.settings_renderer_auto_disabled),
            description = if (uiState.rendererSelectionMode == RendererSelectionMode.AUTO) {
                stringResource(
                    R.string.settings_renderer_auto_current,
                    uiState.autoSelectedRendererBackend.displayName,
                    uiState.autoSelectedRendererBackend.briefProsCons(context)
                )
            } else {
                stringResource(
                    R.string.settings_renderer_manual_current,
                    uiState.manualRendererBackend.displayName,
                    uiState.manualRendererBackend.briefProsCons(context)
                )
            },
            onCheckedChange = { checked ->
                actions.onRendererSelectionModeChanged(
                    if (checked) RendererSelectionMode.AUTO else RendererSelectionMode.MANUAL
                )
            }
        )
    )
    uiState.rendererFallbackText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (uiState.rendererSelectionMode == RendererSelectionMode.MANUAL) {
        SettingsDropdownField(
            label = stringResource(R.string.settings_renderer_manual_label),
            valueText = uiState.manualRendererBackend.displayName,
            enabled = !uiState.busy,
            supportingText = stringResource(
                R.string.settings_renderer_manual_supporting,
                uiState.manualRendererBackend.displayName,
                uiState.manualRendererBackend.briefProsCons(context)
            ),
            options = uiState.rendererBackendOptions,
            optionEnabled = { option -> option.available },
            optionLabel = { option -> option.backend.displayName },
            optionDescription = { option ->
                buildList {
                    add(option.backend.briefProsCons(context))
                    option.reasonText?.let(::add)
                }.joinToString("  ")
            },
            onOptionSelected = { option -> actions.onManualRendererBackendChanged(option.backend) }
        )
    }

    if (uiState.effectiveRendererBackend == RendererBackend.OPENGL_ES_MOBILEGLUES) {
        SettingsActionListItem(
            title = stringResource(R.string.settings_mobileglues_entry_title),
            supportingText = stringResource(
                R.string.settings_mobileglues_entry_summary,
                uiState.mobileGluesAnglePolicy.displayName(context),
                uiState.mobileGluesMultidrawMode.displayName(context),
                uiState.mobileGluesCustomGlVersion.displayName(context)
            ),
            enabled = !uiState.busy,
            onClick = actions.onOpenMobileGluesSettings
        )
    }

    Text(
        text = stringResource(R.string.settings_render_surface_backend_title),
        style = MaterialTheme.typography.bodyMedium
    )
    SettingsDropdownField(
        label = stringResource(R.string.settings_render_surface_backend_title),
        valueText = uiState.renderSurfaceBackend.displayName(context),
        enabled = !uiState.busy,
        supportingText = if (uiState.surfaceBackendForcedByRenderer) {
            stringResource(
                R.string.settings_renderer_surface_forced,
                uiState.effectiveRenderSurfaceBackend.displayName(context)
            )
        } else {
            stringResource(R.string.settings_render_surface_backend_desc)
        },
        supportingTextColor = if (uiState.surfaceBackendForcedByRenderer) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        options = RenderSurfaceBackend.entries,
        optionLabel = { backend -> backend.displayName(context) },
        onOptionSelected = actions.onRenderSurfaceBackendChanged
    )

    SettingsChoiceDialogItem(
        SettingsChoiceSpec(
            title = stringResource(R.string.settings_gpu_resource_guardian_title),
            valueText = gpuResourceGuardianModeDisplayName(uiState.gpuResourceGuardianMode),
            enabled = !uiState.busy,
            selectedValue = uiState.gpuResourceGuardianMode,
            options = GpuResourceGuardianMode.entries,
            optionLabel = { mode -> gpuResourceGuardianModeDisplayName(mode) },
            optionDescription = { mode -> gpuResourceGuardianModeDescription(mode) },
            onOptionSelected = actions.onGpuResourceGuardianModeChanged,
            description = stringResource(R.string.settings_gpu_resource_guardian_desc),
            dialogDescription = null,
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.gpuResourceGuardianPressureDownscaleEnabled,
            enabled = !uiState.busy &&
                uiState.gpuResourceGuardianMode != GpuResourceGuardianMode.OFF &&
                uiState.gpuResourceGuardianMode != GpuResourceGuardianMode.LEGACY,
            enabledText = stringResource(
                R.string.settings_gpu_resource_guardian_pressure_downscale_enabled
            ),
            disabledText = stringResource(
                R.string.settings_gpu_resource_guardian_pressure_downscale_disabled
            ),
            description = stringResource(
                R.string.settings_gpu_resource_guardian_pressure_downscale_desc
            ),
            onCheckedChange = actions.onGpuResourceGuardianPressureDownscaleChanged
        )
    )

    Text(
        text = stringResource(R.string.settings_jvm_heap_title),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = stringResource(R.string.settings_jvm_heap_value_mb, heapSliderValue.roundToInt()),
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = heapSliderValue,
        onValueChange = { value ->
            heapSliderValue = value
            val step = heapSliderToStep(
                value = value,
                min = uiState.jvmHeapMinMb,
                step = uiState.jvmHeapStepMb,
            )
            if (step != lastHeapStep) {
                lastHeapStep = step
                performHapticFeedback(view, HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = { actions.onJvmHeapMaxSelected(heapSliderValue.roundToInt()) },
        valueRange = uiState.jvmHeapMinMb.toFloat()..uiState.jvmHeapMaxMb.toFloat(),
        steps = ((uiState.jvmHeapMaxMb - uiState.jvmHeapMinMb) / uiState.jvmHeapStepMb - 1)
            .coerceAtLeast(0),
        enabled = !uiState.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(R.string.settings_jvm_heap_desc),
        style = MaterialTheme.typography.bodySmall
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.compressedPointersEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_jvm_compressed_pointers_enabled),
            disabledText = stringResource(R.string.settings_jvm_compressed_pointers_disabled),
            description = stringResource(R.string.settings_jvm_compressed_pointers_desc),
            onCheckedChange = actions.onJvmCompressedPointersChanged
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.stringDeduplicationEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_jvm_string_dedup_enabled),
            disabledText = stringResource(R.string.settings_jvm_string_dedup_disabled),
            description = stringResource(R.string.settings_jvm_string_dedup_desc),
            onCheckedChange = actions.onJvmStringDeduplicationChanged
        )
    )
}


internal fun heapSliderToStep(value: Float, min: Int, step: Int): Int {
    val safeStep = step.coerceAtLeast(1)
    return ((value - min.toFloat()) / safeStep.toFloat()).roundToInt()
}


@Composable
internal fun SettingsCompatibilitySection(
    busy: Boolean,
    onOpenCompatibility: () -> Unit,
) {
    SettingsActionListItem(
        title = stringResource(R.string.compat_settings_open),
        enabled = !busy,
        onClick = onOpenCompatibility
    )
}


@Composable
internal fun SettingsStatusSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: StatusSettingsActions,
) {
    val uriHandler = LocalUriHandler.current
    val unplayableModsSheetUrl = stringResource(R.string.settings_unplayable_mods_sheet_url)
    var showStatusDialog by rememberSaveable { mutableStateOf(false) }
    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    var showUnplayableModsDialog by rememberSaveable { mutableStateOf(false) }
    val statusPreview = remember(uiState.statusText) {
        uiState.statusText
            .lineSequence()
            .take(3)
            .joinToString("\n")
    }

    Text(
        text = statusPreview.ifBlank { stringResource(R.string.settings_status_loading) },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.lwjglDebugEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_lwjgl_debug_enabled),
            disabledText = stringResource(R.string.settings_lwjgl_debug_disabled),
            description = stringResource(R.string.settings_lwjgl_debug_desc),
            onCheckedChange = actions.onLwjglDebugChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.preloadAllJreLibrariesEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_preload_all_jre_enabled),
            disabledText = stringResource(R.string.settings_preload_all_jre_disabled),
            description = stringResource(R.string.settings_preload_all_jre_desc),
            onCheckedChange = actions.onPreloadAllJreLibrariesChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.logcatCaptureEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_logcat_capture_enabled),
            disabledText = stringResource(R.string.settings_logcat_capture_disabled),
            description = stringResource(R.string.settings_logcat_capture_desc),
            onCheckedChange = actions.onLogcatCaptureChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.launcherLogcatCaptureEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_launcher_logcat_capture_enabled),
            disabledText = stringResource(R.string.settings_launcher_logcat_capture_disabled),
            description = stringResource(R.string.settings_launcher_logcat_capture_desc),
            onCheckedChange = actions.onLauncherLogcatCaptureChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.jvmLogcatMirrorEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_jvm_logcat_mirror_enabled),
            disabledText = stringResource(R.string.settings_jvm_logcat_mirror_disabled),
            description = stringResource(R.string.settings_jvm_logcat_mirror_desc),
            onCheckedChange = actions.onJvmLogcatMirrorChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.gpuResourceDiagEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_gpu_resource_diag_enabled),
            disabledText = stringResource(R.string.settings_gpu_resource_diag_disabled),
            description = stringResource(R.string.settings_gpu_resource_diag_desc),
            onCheckedChange = actions.onGpuResourceDiagChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.gdxPadCursorDebugEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_gdx_pad_cursor_debug_enabled),
            disabledText = stringResource(R.string.settings_gdx_pad_cursor_debug_disabled),
            description = stringResource(R.string.settings_gdx_pad_cursor_debug_desc),
            onCheckedChange = actions.onGdxPadCursorDebugChanged
        )
    )
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.glBridgeSwapHeartbeatDebugEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_glbridge_swap_heartbeat_enabled),
            disabledText = stringResource(R.string.settings_glbridge_swap_heartbeat_disabled),
            description = stringResource(R.string.settings_glbridge_swap_heartbeat_desc),
            onCheckedChange = actions.onGlBridgeSwapHeartbeatDebugChanged
        )
    )

    HorizontalDivider()

    SettingsActionListItem(
        title = stringResource(R.string.settings_view_full_status),
        enabled = uiState.statusText.isNotBlank(),
        onClick = { showStatusDialog = true }
    )
    SettingsActionListItem(
        title = stringResource(R.string.settings_view_log_paths),
        enabled = uiState.logPathText.isNotBlank(),
        onClick = { showLogDialog = true }
    )
    SettingsActionListItem(
        title = stringResource(R.string.settings_unplayable_mods_entry_title),
        enabled = true,
        onClick = { showUnplayableModsDialog = true }
    )

    if (showStatusDialog) {
        val emptyStatusInfo = stringResource(R.string.settings_status_info_empty)
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text(stringResource(R.string.settings_status_info_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.statusText.ifBlank { emptyStatusInfo },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showStatusDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

    if (showLogDialog) {
        val emptyLogPaths = stringResource(R.string.settings_log_paths_empty)
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(stringResource(R.string.settings_log_paths_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.logPathText.ifBlank { emptyLogPaths },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showLogDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

    if (showUnplayableModsDialog) {
        AlertDialog(
            onDismissRequest = { showUnplayableModsDialog = false },
            title = { Text(stringResource(R.string.settings_unplayable_mods_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_unplayable_mods_dialog_message),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showUnplayableModsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}


@Composable
internal fun gpuResourceGuardianModeDisplayName(mode: GpuResourceGuardianMode): String {
    return stringResource(
        when (mode) {
            GpuResourceGuardianMode.OFF -> R.string.settings_gpu_resource_guardian_mode_off
            GpuResourceGuardianMode.SAFE -> R.string.settings_gpu_resource_guardian_mode_safe
            GpuResourceGuardianMode.AGGRESSIVE -> R.string.settings_gpu_resource_guardian_mode_aggressive
            GpuResourceGuardianMode.ULTRA_AGGRESSIVE ->
                R.string.settings_gpu_resource_guardian_mode_ultra_aggressive
            GpuResourceGuardianMode.LEGACY -> R.string.settings_gpu_resource_guardian_mode_legacy
        }
    )
}


@Composable
internal fun gpuResourceGuardianModeDescription(mode: GpuResourceGuardianMode): String {
    return stringResource(
        when (mode) {
            GpuResourceGuardianMode.OFF -> R.string.settings_gpu_resource_guardian_mode_off_desc
            GpuResourceGuardianMode.SAFE -> R.string.settings_gpu_resource_guardian_mode_safe_desc
            GpuResourceGuardianMode.AGGRESSIVE ->
                R.string.settings_gpu_resource_guardian_mode_aggressive_desc
            GpuResourceGuardianMode.ULTRA_AGGRESSIVE ->
                R.string.settings_gpu_resource_guardian_mode_ultra_aggressive_desc
            GpuResourceGuardianMode.LEGACY -> R.string.settings_gpu_resource_guardian_mode_legacy_desc
        }
    )
}


internal fun RenderSurfaceBackend.displayName(context: Context): String {
    return when (this) {
        RenderSurfaceBackend.SURFACE_VIEW ->
            context.getString(R.string.settings_render_surface_backend_surface_view_short)
        RenderSurfaceBackend.TEXTURE_VIEW ->
            context.getString(R.string.settings_render_surface_backend_texture_view_short)
    }
}


internal fun RendererBackend.briefProsCons(context: Context): String {
    return when (this) {
        RendererBackend.OPENGL_ES_MOBILEGLUES ->
            context.getString(R.string.settings_renderer_pros_cons_mobileglues)
        RendererBackend.OPENGL_ES2_NATIVE ->
            context.getString(R.string.settings_renderer_pros_cons_native)
        RendererBackend.OPENGL_ES2_GL4ES ->
            context.getString(R.string.settings_renderer_pros_cons_gl4es)
        RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER ->
            context.getString(R.string.settings_renderer_pros_cons_kopper)
        RendererBackend.VULKAN_ZINK ->
            context.getString(R.string.settings_renderer_pros_cons_vulkan_zink)
    }
}


