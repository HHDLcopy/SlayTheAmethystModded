package io.stamethyst.ui.workshop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.stamethyst.R

@Composable
internal fun WorkshopLoadFailureHint() {
    Text(
        text = stringResource(R.string.workshop_load_failure_steam_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}
