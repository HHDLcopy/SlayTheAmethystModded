package io.stamethyst.ui.feedback

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.R
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.backend.feedback.FeedbackIssueBrowseItem
import io.stamethyst.backend.feedback.FeedbackIssueSyncService
import io.stamethyst.ui.LauncherTransientNoticeBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Stable
class FeedbackIssueBrowserViewModel : ViewModel() {
    companion object {
        private const val TAG = "FeedbackIssueBrowser"
    }

    enum class IssueStateFilter(@StringRes val labelResId: Int) {
        ALL(R.string.feedback_filter_all),
        OPEN_ONLY(R.string.feedback_filter_open),
        CLOSED_ONLY(R.string.feedback_filter_closed);

        fun matches(issue: FeedbackIssueBrowseItem): Boolean {
            return when (this) {
                ALL -> true
                OPEN_ONLY -> !issue.isClosed
                CLOSED_ONLY -> issue.isClosed
            }
        }

        val browseState: String
            get() = when (this) {
                ALL -> "all"
                OPEN_ONLY -> "open"
                CLOSED_ONLY -> "closed"
            }
    }

    data class UiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val loadingMore: Boolean = false,
        val issues: List<FeedbackIssueBrowseItem> = emptyList(),
        val nextPage: Int = 1,
        val hasMore: Boolean = true,
        val initialLoaded: Boolean = false,
        val issueStateFilter: IssueStateFilter = IssueStateFilter.OPEN_ONLY,
        val searchQuery: String = "",
        val refreshingIssues: Boolean = false
    ) {
        val visibleIssues: List<FeedbackIssueBrowseItem>
            get() = issues.asSequence()
                .filter(issueStateFilter::matches)
                .sortedWith(
                    compareByDescending<FeedbackIssueBrowseItem> { it.updatedAtMs }
                        .thenByDescending { it.issueNumber }
                )
                .toList()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingCriteriaRefresh = false

    var uiState by mutableStateOf(UiState())
        private set

    fun bind(host: Activity) {
        FeedbackInboxCoordinator.bind(host.applicationContext)
        if (!uiState.initialLoaded && !uiState.busy && !uiState.loadingMore) {
            loadPage(host, reset = true)
        }
    }

    fun onRefresh(host: Activity) {
        if (uiState.busy || uiState.loadingMore) {
            return
        }
        loadPage(host, reset = true)
    }

    fun onLoadMore(host: Activity) {
        if (uiState.busy || uiState.loadingMore || !uiState.hasMore) {
            return
        }
        loadPage(host, reset = false)
    }

    fun onIssueStateFilterSelected(host: Activity, filter: IssueStateFilter) {
        if (uiState.issueStateFilter == filter) {
            return
        }
        uiState = uiState.copy(issueStateFilter = filter)
        refreshForCriteriaChange(host)
    }

    fun onSearchQueryChanged(host: Activity, query: String) {
        if (uiState.searchQuery == query) {
            return
        }
        uiState = uiState.copy(searchQuery = query)
        refreshForCriteriaChange(host)
    }

    fun onSubscribe(host: Activity, issueNumber: Long) {
        if (uiState.busy || uiState.loadingMore) {
            return
        }
        setBusy(true, host.getString(R.string.feedback_busy_following_issue))
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.subscribeToIssue(host, issueNumber)
            }.onSuccess { result ->
                FeedbackInboxCoordinator.refreshFromStorage(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    val displacedIssueNumber = result.displacedSubscriptions.firstOrNull()?.issueNumber
                    LauncherTransientNoticeBus.show(
                        host,
                        if (displacedIssueNumber != null) {
                            host.getString(
                                R.string.feedback_follow_success_with_replacement,
                                result.subscription.issueNumber,
                                displacedIssueNumber
                            )
                        } else {
                            host.getString(
                                R.string.feedback_follow_success,
                                result.subscription.issueNumber
                            )
                        },
                        Toast.LENGTH_LONG
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to follow issue", error)
                host.runOnUiThread {
                    setBusy(false, null)
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_follow_failed,
                            error.toReadableMessage(host)
                        ),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }

    private fun loadPage(host: Activity, reset: Boolean) {
        if (reset) {
            pendingCriteriaRefresh = false
            uiState = uiState.copy(
                busy = true,
                busyMessage = host.getString(R.string.feedback_busy_loading_issues),
                refreshingIssues = true
            )
        } else {
            uiState = uiState.copy(loadingMore = true)
        }
        val requestPage = if (reset) 1 else uiState.nextPage
        val requestSearchQuery = uiState.searchQuery
        val requestBrowseState = uiState.issueStateFilter.browseState
        executor.execute {
            runCatching {
                FeedbackIssueSyncService.listIssues(
                    context = host,
                    page = requestPage,
                    searchQuery = requestSearchQuery,
                    state = requestBrowseState
                )
            }.onSuccess { page ->
                host.runOnUiThread {
                    val mergedIssues = if (reset) {
                        page.issues
                    } else {
                        mergeIssues(uiState.issues, page.issues)
                    }
                    uiState = uiState.copy(
                        busy = false,
                        busyMessage = null,
                        loadingMore = false,
                        refreshingIssues = false,
                        issues = mergedIssues,
                        nextPage = page.nextPage,
                        hasMore = page.hasMore,
                        initialLoaded = true
                    )
                    refreshPendingCriteriaIfNeeded(host)
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load issue browser page", error)
                host.runOnUiThread {
                    uiState = uiState.copy(
                        busy = false,
                        busyMessage = null,
                        loadingMore = false,
                        refreshingIssues = false,
                        initialLoaded = true
                    )
                    LauncherTransientNoticeBus.show(
                        host,
                        host.getString(
                            R.string.feedback_issue_browser_load_failed,
                            error.toReadableMessage(host)
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshPendingCriteriaIfNeeded(host)
                }
            }
        }
    }

    private fun refreshForCriteriaChange(host: Activity) {
        if (uiState.busy || uiState.loadingMore) {
            pendingCriteriaRefresh = true
            return
        }
        loadPage(host, reset = true)
    }

    private fun refreshPendingCriteriaIfNeeded(host: Activity) {
        if (!pendingCriteriaRefresh) {
            return
        }
        pendingCriteriaRefresh = false
        loadPage(host, reset = true)
    }

    private fun mergeIssues(
        existing: List<FeedbackIssueBrowseItem>,
        appended: List<FeedbackIssueBrowseItem>
    ): List<FeedbackIssueBrowseItem> {
        if (appended.isEmpty()) {
            return existing
        }
        val merged = LinkedHashMap<Long, FeedbackIssueBrowseItem>(existing.size + appended.size)
        existing.forEach { merged[it.issueNumber] = it }
        appended.forEach { merged[it.issueNumber] = it }
        return merged.values.toList()
    }

    private fun setBusy(busy: Boolean, message: String?) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyMessage = message,
                refreshingIssues = false
            )
        } else {
            uiState.copy(
                busy = false,
                busyMessage = null,
                refreshingIssues = false
            )
        }
    }

    private fun Throwable.toReadableMessage(host: Activity): String {
        val rawMessage = message?.trim().orEmpty()
        if (rawMessage.isNotEmpty() && rawMessage != javaClass.name) {
            return rawMessage
        }
        val causeMessage = cause?.message?.trim().orEmpty()
        if (causeMessage.isNotEmpty()) {
            return "${javaClass.simpleName}: $causeMessage"
        }
        return javaClass.simpleName.ifBlank { host.getString(R.string.feedback_unknown_error) }
    }
}
