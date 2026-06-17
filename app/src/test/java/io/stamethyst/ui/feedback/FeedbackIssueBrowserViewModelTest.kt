package io.stamethyst.ui.feedback

import io.stamethyst.backend.feedback.FeedbackIssueBrowseItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackIssueBrowserViewModelTest {
    @Test
    fun uiStateVisibleIssues_sortedNewestFirst() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
                issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
                issue(issueNumber = 21, updatedAtMs = 500L, isClosed = false),
                issue(issueNumber = 5, updatedAtMs = 100L, isClosed = true)
            ),
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.ALL
        )

        assertEquals(listOf(21L, 18L, 12L, 5L), uiState.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_defaultsToOpenOnly() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
                issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
                issue(issueNumber = 21, updatedAtMs = 300L, isClosed = false)
            )
        )

        assertEquals(listOf(21L, 12L), uiState.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_appliesIssueStateFilter() {
        val issues = listOf(
            issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
            issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
            issue(issueNumber = 21, updatedAtMs = 300L, isClosed = false)
        )

        val openOnly = FeedbackIssueBrowserViewModel.UiState(
            issues = issues,
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.OPEN_ONLY
        )
        val closedOnly = FeedbackIssueBrowserViewModel.UiState(
            issues = issues,
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.CLOSED_ONLY
        )

        assertEquals(listOf(21L, 12L), openOnly.visibleIssues.map { it.issueNumber })
        assertEquals(listOf(18L), closedOnly.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_doesNotLocallyFilterSearchResults() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(
                    issueNumber = 12,
                    updatedAtMs = 200L,
                    isClosed = false,
                    title = "Launcher crash on startup"
                ),
                issue(
                    issueNumber = 18,
                    updatedAtMs = 500L,
                    isClosed = false,
                    bodyPreview = "Rendering artifacts after entering combat"
                ),
                issue(
                    issueNumber = 21,
                    updatedAtMs = 300L,
                    isClosed = false,
                    authorLabel = "amethyst-user"
                )
            ),
            searchQuery = "artifact"
        )

        assertEquals(listOf(18L, 21L, 12L), uiState.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_appliesStateFilterWhenSearchQueryIsPresent() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false, title = "Card crash"),
                issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true, title = "Card crash"),
                issue(issueNumber = 21, updatedAtMs = 300L, isClosed = false, title = "Display issue")
            ),
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.CLOSED_ONLY,
            searchQuery = "card"
        )

        assertEquals(listOf(18L), uiState.visibleIssues.map { it.issueNumber })
    }

    private fun issue(
        issueNumber: Long,
        updatedAtMs: Long,
        isClosed: Boolean,
        title: String = "Issue #$issueNumber",
        bodyPreview: String = "",
        authorLabel: String = "tester"
    ): FeedbackIssueBrowseItem {
        return FeedbackIssueBrowseItem(
            issueNumber = issueNumber,
            issueUrl = "https://example.com/issues/$issueNumber",
            title = title,
            bodyPreview = bodyPreview,
            state = if (isClosed) "closed" else "open",
            commentCount = 0,
            authorLabel = authorLabel,
            updatedAtMs = updatedAtMs
        )
    }
}
