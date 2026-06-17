package io.stamethyst.ui.feedback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun LauncherFeedbackIssuePreviewScreen(
    issueNumber: Long,
    modifier: Modifier = Modifier
) {
    LauncherFeedbackConversationScreen(
        issueNumber = issueNumber,
        modifier = modifier
    )
}
