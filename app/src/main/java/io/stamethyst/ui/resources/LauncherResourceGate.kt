package io.stamethyst.ui.resources

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.resources.ExternalResourcePackService
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FULL_RELEASE_PAGE_URL =
    "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/latest"

@Composable
fun LauncherResourceGate(
    modifier: Modifier = Modifier,
    onResourcesReady: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    var gateState by remember {
        mutableStateOf<ResourceGateState>(
            if (ExternalResourcePackService.isAvailable(applicationContext)) {
                ResourceGateState.Ready
            } else {
                ResourceGateState.Preparing(
                    percent = 0,
                    message = context.progressText(R.string.startup_progress_checking_external_resources)
                )
            }
        )
    }
    var selectedMirror by remember {
        mutableStateOf(UpdateMirrorManager.current(context))
    }
    var retryNonce by remember { mutableIntStateOf(0) }
    var readyNotified by remember { mutableStateOf(false) }

    LaunchedEffect(retryNonce) {
        if (retryNonce == 0 && ExternalResourcePackService.isAvailable(applicationContext)) {
            gateState = ResourceGateState.Ready
            return@LaunchedEffect
        }
        readyNotified = false
        gateState = ResourceGateState.Preparing(
            percent = 0,
            message = context.progressText(R.string.startup_progress_checking_external_resources)
        )
        runCatching {
            withContext(Dispatchers.IO) {
                ExternalResourcePackService.ensureAvailable(applicationContext) { percent, message ->
                    gateState = ResourceGateState.Preparing(
                        percent = percent.coerceIn(0, 100),
                        message = message
                    )
                }
            }
        }.fold(
            onSuccess = {
                gateState = ResourceGateState.Ready
            },
            onFailure = { error ->
                gateState = ResourceGateState.Failed(
                    summary = GithubMirrorFallback.summarize(error)
                        .ifBlank { error.javaClass.simpleName }
                )
            }
        )
    }

    if (gateState is ResourceGateState.Ready) {
        LaunchedEffect(Unit) {
            if (!readyNotified) {
                readyNotified = true
                onResourcesReady()
            }
        }
        content()
        return
    }

    ResourcePreparationScreen(
        state = gateState,
        selectedMirror = selectedMirror,
        availableMirrors = remember { UpdateMirrorManager.selectableSources() },
        onMirrorSelected = { source ->
            selectedMirror = source
            UpdateMirrorManager.saveCurrent(context, source)
            retryNonce++
        },
        onRetry = { retryNonce++ },
        onOpenFullRelease = { openExternalUrl(context, FULL_RELEASE_PAGE_URL) },
        modifier = modifier
    )
}

@Composable
private fun ResourcePreparationScreen(
    state: ResourceGateState,
    selectedMirror: UpdateSource,
    availableMirrors: List<UpdateSource>,
    onMirrorSelected: (UpdateSource) -> Unit,
    onRetry: () -> Unit,
    onOpenFullRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMirrorDialog by remember { mutableStateOf(false) }
    val preparing = state as? ResourceGateState.Preparing
    val failed = state as? ResourceGateState.Failed

    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.resource_gate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.resource_gate_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (preparing != null) {
                    val progress = preparing.percent.coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.resource_gate_progress_percent, progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = preparing.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                MirrorSelectionRow(
                    selectedMirror = selectedMirror,
                    enabled = preparing == null,
                    onClick = { showMirrorDialog = true }
                )

                if (failed != null) {
                    Text(
                        text = stringResource(R.string.resource_gate_failure_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    SelectionContainer {
                        Text(
                            text = failed.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.resource_gate_full_release_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.resource_gate_retry))
                        }
                        OutlinedButton(
                            onClick = onOpenFullRelease,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.resource_gate_open_full_release))
                        }
                    }
                }
            }
        }
    }

    if (showMirrorDialog) {
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
            title = { Text(stringResource(R.string.resource_gate_mirror_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableMirrors.forEach { source ->
                        MirrorOptionRow(
                            selected = selectedMirror == source,
                            text = source.displayName,
                            onClick = {
                                showMirrorDialog = false
                                onMirrorSelected(source)
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.resource_gate_mirror_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMirrorDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }
}

@Composable
private fun MirrorSelectionRow(
    selectedMirror: UpdateSource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.resource_gate_mirror_title),
            style = MaterialTheme.typography.labelLarge
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedMirror.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.resource_gate_mirror_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MirrorOptionRow(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private sealed interface ResourceGateState {
    data class Preparing(
        val percent: Int,
        val message: String
    ) : ResourceGateState

    data object Ready : ResourceGateState

    data class Failed(
        val summary: String
    ) : ResourceGateState
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    } catch (_: IllegalArgumentException) {
    }
}
