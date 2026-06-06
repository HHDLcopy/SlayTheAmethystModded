package io.stamethyst.ui.workshop

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.stamethyst.R
import io.stamethyst.backend.workshop.BaiduAiTextTranslationClient
import io.stamethyst.backend.workshop.BaiduTranslationApiException
import io.stamethyst.backend.workshop.BaiduTranslationCredentials
import io.stamethyst.backend.workshop.BaiduTranslationCredentialsRepository
import io.stamethyst.backend.workshop.WorkshopBrowseQuery
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopBrowseTimeFilter
import io.stamethyst.backend.workshop.WorkshopChangeNotes
import io.stamethyst.backend.workshop.WorkshopDownloadBlocklist
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCategory
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.WorkshopSteamLoginRequiredException
import io.stamethyst.backend.workshop.WorkshopSubscriptionVerificationException
import io.stamethyst.backend.workshop.WorkshopUnsubscriptionVerificationException
import io.stamethyst.backend.workshop.WorkshopUpdateCheckResult
import io.stamethyst.backend.workshop.WorkshopUpdateChecker
import io.stamethyst.backend.workshop.buildBaiduModDescriptionReference
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.backend.workshop.isRunningDownload
import io.stamethyst.backend.workshop.mapLocaleLanguageToBaiduLanguage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Stable
internal class WorkshopViewModel : ViewModel() {
    var uiState by mutableStateOf(WorkshopUiState())
        private set

    private var service: WorkshopService? = null
    private var metadataStore: WorkshopMetadataStore? = null
    private var loaded = false
    private var activeListMode: WorkshopListMode = WorkshopListMode.Browse
    private var activeQueryText: String = ""
    private var activeSort: WorkshopBrowseSort = WorkshopBrowseSort.MostPopular
    private var activeTimeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek
    private var activeCategory: WorkshopModCategory = WorkshopModCategory.All
    private var browseRequestGeneration = 0
    private var refreshDownloadStateJob: Job? = null
    private val detailsCache = mutableMapOf<String, WorkshopItemDetails>()
    private val detailLoadsInFlight = mutableSetOf<String>()
    private val translationClient = BaiduAiTextTranslationClient()
    private val downloadTaskPersistenceMutex = Mutex()

    fun load(context: Context, initialListMode: WorkshopListMode = WorkshopListMode.Browse) {
        WorkshopDownloadCenterStore.initialize(context)
        if (loaded) {
            refreshDownloadState(context)
            if (uiState.listMode != initialListMode) {
                when (initialListMode) {
                    WorkshopListMode.Browse -> showWorkshopBrowse(context)
                    WorkshopListMode.Subscriptions -> showSubscribedWorkshopMods(context)
                }
            }
            return
        }
        loaded = true
        activeListMode = initialListMode
        service = WorkshopService(context)
        metadataStore = WorkshopMetadataStore(context)
        metadataStore?.markMissingFiles()
        val steamLoggedIn = service?.hasSteamAuth() == true
        uiState = uiState.copy(
            steamLoggedIn = steamLoggedIn,
            listMode = activeListMode,
            installedMods = metadataStore?.list().orEmpty(),
        )
        viewModelScope.launch(Dispatchers.IO) {
            WorkshopDownloadProcessService.startNextQueued(context)
        }
        refreshDownloadState(context)
        when (activeListMode) {
            WorkshopListMode.Browse -> search(context, "")
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = 1, append = false)
        }
    }

    private fun findCachedDetails(appId: UInt, publishedFileId: ULong): WorkshopItemDetails? {
        return uiState.selected?.takeIf { selected ->
            selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
        } ?: detailsCache[detailsCacheKey(appId, publishedFileId)]
    }

    private fun findSummaryFallback(appId: UInt, publishedFileId: ULong): WorkshopItemSummary? {
        uiState.items.firstOrNull { item ->
            item.appId == appId && item.publishedFileId == publishedFileId
        }?.let { return it }
        uiState.selected?.takeIf { selected ->
            selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
        }?.summary?.let { return it }
        detailsCache[detailsCacheKey(appId, publishedFileId)]?.summary?.let { return it }
        uiState.selected?.dependencies?.firstOrNull { dependency ->
            dependency.appId == appId && dependency.publishedFileId == publishedFileId
        }?.let { return it }
        return uiState.installedMods.firstOrNull { record ->
            record.appId == appId && record.publishedFileId == publishedFileId
        }?.toWorkshopItemSummary()
    }

    fun refreshDownloadState(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        if (refreshDownloadStateJob?.isActive == true) return
        val currentMetadataStore = metadataStore
        val currentService = service
        refreshDownloadStateJob = viewModelScope.launch {
            val (loadedTasks, installedMods, steamLoggedIn) = withContext(Dispatchers.IO) {
                currentMetadataStore?.markMissingFiles()
                Triple(
                    WorkshopDownloadCenterStore.loadTasksWithRecovery(context),
                    currentMetadataStore?.list().orEmpty(),
                    currentService?.hasSteamAuth() == true,
                )
            }
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
            uiState = uiState.copy(
                steamLoggedIn = steamLoggedIn,
                listMode = activeListMode,
                installedMods = installedMods,
                downloadInProgress = loadedTasks.any { it.status.isActiveDownload() },
            )
        }
    }

    fun refreshDownloadTaskState(context: Context) {
        WorkshopDownloadCenterStore.initialize(context)
        if (refreshDownloadStateJob?.isActive == true) return
        val currentMetadataStore = metadataStore
        val currentService = service
        refreshDownloadStateJob = viewModelScope.launch {
            val loadedTasks = withContext(Dispatchers.IO) {
                WorkshopDownloadCenterStore.loadTasksWithRecovery(context)
            }
            WorkshopDownloadCenterStore.replaceInMemory(loadedTasks)
            val hasActiveDownload = loadedTasks.any { task -> task.status.isActiveDownload() }
            if (hasActiveDownload) {
                if (!uiState.downloadInProgress) {
                    uiState = uiState.copy(downloadInProgress = true)
                }
                return@launch
            }

            val (installedMods, steamLoggedIn) = withContext(Dispatchers.IO) {
                currentMetadataStore?.markMissingFiles()
                Pair(
                    currentMetadataStore?.list().orEmpty(),
                    currentService?.hasSteamAuth() == true,
                )
            }
            uiState = uiState.copy(
                steamLoggedIn = steamLoggedIn,
                listMode = activeListMode,
                installedMods = installedMods,
                downloadInProgress = false,
            )
        }
    }

    private fun updateDownloadTaskAsync(
        publishedFileId: ULong,
        transform: (WorkshopDownloadTaskUi) -> WorkshopDownloadTaskUi,
    ): WorkshopDownloadTaskUi? {
        val updatedTask = WorkshopDownloadCenterStore.updateInMemory(publishedFileId, transform)
        persistDownloadTaskAsync {
            if (updatedTask != null) {
                WorkshopDownloadCenterStore.persistUpsert(updatedTask)
            } else {
                WorkshopDownloadCenterStore.persistUpdate(publishedFileId, transform)
            }
        }
        return updatedTask
    }

    private fun persistDownloadTaskAsync(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadTaskPersistenceMutex.withLock {
                block()
            }
        }
    }

    fun refreshSteamAuth(context: Context) {
        val currentService = service ?: return
        val steamLoggedIn = currentService.hasSteamAuth()
        if (!steamLoggedIn && activeListMode == WorkshopListMode.Subscriptions) {
            uiState = uiState.copy(
                browseLoading = false,
                loadingMore = false,
                listMode = WorkshopListMode.Subscriptions,
                steamLoggedIn = false,
                items = emptyList(),
                nextPage = 1,
                hasMorePages = false,
                subscribedWorkshopIds = emptySet(),
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                errorMessage = null,
            )
            return
        }

        if (uiState.steamLoggedIn != steamLoggedIn) {
            uiState = if (steamLoggedIn) {
                uiState.copy(steamLoggedIn = true)
            } else {
                uiState.copy(
                    steamLoggedIn = false,
                    subscribedWorkshopIds = emptySet(),
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                )
            }
        }
        if (steamLoggedIn && activeListMode == WorkshopListMode.Subscriptions && !uiState.browseLoading) {
            loadSubscribedPage(context, page = 1, append = false)
        }
    }

    fun search(
        context: Context,
        queryText: String,
        sort: WorkshopBrowseSort = WorkshopBrowseSort.MostPopular,
        timeFilter: WorkshopBrowseTimeFilter = WorkshopBrowseTimeFilter.OneWeek,
        category: WorkshopModCategory = WorkshopModCategory.All,
    ) {
        activeListMode = WorkshopListMode.Browse
        activeQueryText = queryText
        activeSort = sort
        activeTimeFilter = timeFilter
        activeCategory = category
        loadBrowsePage(context, queryText = queryText, page = 1, append = false)
    }

    fun refreshBrowse(context: Context) {
        when (activeListMode) {
            WorkshopListMode.Browse -> loadBrowsePage(
                context = context,
                queryText = activeQueryText,
                page = 1,
                append = false,
                clearItems = false,
            )
            WorkshopListMode.Subscriptions -> loadSubscribedPage(
                context = context,
                page = 1,
                append = false,
                clearItems = false,
            )
        }
    }

    fun loadNextPage(context: Context) {
        val state = uiState
        if (state.browseLoading || state.loadingMore || !state.hasMorePages) return
        when (state.listMode) {
            WorkshopListMode.Browse -> loadBrowsePage(context, queryText = activeQueryText, page = state.nextPage, append = true)
            WorkshopListMode.Subscriptions -> loadSubscribedPage(context, page = state.nextPage, append = true)
        }
    }

    fun showSubscribedWorkshopMods(context: Context) {
        activeListMode = WorkshopListMode.Subscriptions
        loadSubscribedPage(context, page = 1, append = false)
    }

    fun showWorkshopBrowse(context: Context) {
        activeListMode = WorkshopListMode.Browse
        loadBrowsePage(context, queryText = activeQueryText, page = 1, append = false)
    }

    private fun loadBrowsePage(
        context: Context,
        queryText: String,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Browse
        val requestGeneration = ++browseRequestGeneration
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Browse,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browse(
                        WorkshopBrowseQuery(
                            searchText = queryText,
                            sort = activeSort,
                            timeFilter = activeTimeFilter,
                            category = activeCategory,
                            page = page,
                            pageSize = WorkshopUiState.PAGE_SIZE,
                        )
                    )
                }
            }.onSuccess { result ->
                if (activeListMode != WorkshopListMode.Browse || requestGeneration != browseRequestGeneration) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    errorMessage = if (merged.isEmpty()) context.getString(R.string.workshop_error_no_entries_found) else null,
                )
            }.onFailure { error ->
                if (activeListMode != WorkshopListMode.Browse || requestGeneration != browseRequestGeneration) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun loadSubscribedPage(
        context: Context,
        page: Int,
        append: Boolean,
        clearItems: Boolean = true,
    ) {
        val currentService = service ?: return
        activeListMode = WorkshopListMode.Subscriptions
        if (!currentService.hasSteamAuth()) {
            uiState = uiState.copy(
                browseLoading = false,
                loadingMore = false,
                listMode = WorkshopListMode.Subscriptions,
                steamLoggedIn = false,
                items = emptyList(),
                nextPage = 1,
                hasMorePages = false,
                subscribedWorkshopIds = emptySet(),
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                errorMessage = null,
            )
            return
        }
        viewModelScope.launch {
            uiState = if (append) {
                uiState.copy(loadingMore = true, errorMessage = null)
            } else {
                uiState.copy(
                    browseLoading = true,
                    loadingMore = false,
                    listMode = WorkshopListMode.Subscriptions,
                    errorMessage = null,
                    items = if (clearItems) emptyList() else uiState.items,
                    nextPage = 1,
                    hasMorePages = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.browseSubscriptions(page = page, pageSize = WorkshopUiState.PAGE_SIZE)
                }
            }.onSuccess { result ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onSuccess
                val existing = if (append) uiState.items else emptyList()
                val merged = (existing + result.items).distinctBy { it.publishedFileId }
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    items = merged,
                    nextPage = page + 1,
                    hasMorePages = result.hasNextPage,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds + result.items.map { it.publishedFileId },
                    errorMessage = null,
                )
            }.onFailure { error ->
                if (activeListMode != WorkshopListMode.Subscriptions) return@onFailure
                uiState = uiState.copy(
                    browseLoading = false,
                    loadingMore = false,
                    steamLoggedIn = currentService.hasSteamAuth(),
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun openDetails(context: Context, item: WorkshopItemSummary) {
        loadDetails(context, item.appId, item.publishedFileId, item)
    }

    fun loadDetails(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
        fallbackSummary: WorkshopItemSummary? = null,
        clearSelected: Boolean = false,
    ) {
        val currentService = service ?: return
        val detailKey = detailsCacheKey(appId, publishedFileId)
        if (detailKey in detailLoadsInFlight) return
        if (!clearSelected) {
            findCachedDetails(appId, publishedFileId)
                ?.takeIf { cachedDetails -> cachedDetails.canReuseForDetailOpen(fallbackSummary) }
                ?.let { cachedDetails ->
                    showLoadedDetails(
                        context = context,
                        currentService = currentService,
                        details = cachedDetails,
                        refreshSubscriptionStatus = shouldRefreshCachedDetailSubscriptionStatus(
                            currentService = currentService,
                            publishedFileId = publishedFileId,
                        ),
                    )
                    return
                }
        }
        detailLoadsInFlight += detailKey
        viewModelScope.launch {
            try {
                uiState = uiState.copy(
                    selected = if (clearSelected) null else uiState.selected,
                    detailLoadingId = publishedFileId,
                    errorMessage = null,
                    commentLoadingId = null,
                    commentErrorMessage = null,
                    detailTranslationModeKey = null,
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = null,
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = null,
                    detailSubscriptionLoadingId = null,
                    detailSubscriptionMessage = null,
                    detailSubscriptionStatusId = publishedFileId,
                    detailSubscriptionStatus = if (currentService.hasSteamAuth()) {
                        WorkshopDetailSubscriptionStatus.Checking
                    } else {
                        WorkshopDetailSubscriptionStatus.Unknown
                    },
                )
                runCatching {
                    val cachedDetails = findCachedDetails(appId, publishedFileId)
                    val summaryFallback = cachedDetails?.summary ?: fallbackSummary ?: findSummaryFallback(appId, publishedFileId)
                    withContext(Dispatchers.IO) { currentService.getDetails(appId, publishedFileId, summaryFallback) }
                }.onSuccess { loadedDetails ->
                    val details = loadedDetails.mergeCachedCommunityData(findCachedDetails(appId, publishedFileId))
                    showLoadedDetails(context, currentService, details)
                }.onFailure { error ->
                    uiState = uiState.copy(
                        detailLoadingId = null,
                        detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                detailLoadsInFlight -= detailKey
            }
        }
    }

    private fun showLoadedDetails(
        context: Context,
        currentService: WorkshopService,
        details: WorkshopItemDetails,
        refreshSubscriptionStatus: Boolean = true,
    ) {
        detailsCache[details.cacheKey()] = details
        val summary = details.summary
        val shouldLoadComments = details.shouldLoadInitialWorkshopComments()
        val steamLoggedIn = currentService.hasSteamAuth()
        uiState = uiState.copy(
            selected = details,
            detailLoadingId = null,
            errorMessage = null,
            commentLoadingId = if (shouldLoadComments) summary.publishedFileId else null,
            commentErrorMessage = details.commentUnavailableMessage(context),
            detailSubscriptionStatusId = summary.publishedFileId,
            detailSubscriptionStatus = if (!steamLoggedIn) {
                WorkshopDetailSubscriptionStatus.Unknown
            } else if (refreshSubscriptionStatus) {
                WorkshopDetailSubscriptionStatus.Checking
            } else if (summary.publishedFileId in uiState.subscribedWorkshopIds) {
                WorkshopDetailSubscriptionStatus.Subscribed
            } else {
                uiState.detailSubscriptionStatusFor(summary.publishedFileId)
            },
        )
        if (shouldLoadComments) {
            loadWorkshopCommentsPage(context, summary.appId, summary.publishedFileId, page = 1)
        }
        if (refreshSubscriptionStatus) {
            refreshDetailSubscriptionStatus(summary.appId, summary.publishedFileId)
        }
    }

    private fun shouldRefreshCachedDetailSubscriptionStatus(
        currentService: WorkshopService,
        publishedFileId: ULong,
    ): Boolean =
        currentService.hasSteamAuth() &&
            publishedFileId !in uiState.subscribedWorkshopIds &&
            uiState.detailSubscriptionStatusFor(publishedFileId) == WorkshopDetailSubscriptionStatus.Unknown

    fun retryDetailsLoad(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
    ) {
        loadDetails(
            context = context,
            appId = appId,
            publishedFileId = publishedFileId,
            clearSelected = true,
        )
    }

    fun loadSelectedChangeNotes(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val changeNotesKey = details.cacheKey()
        if (uiState.detailChangeNotes.containsKey(changeNotesKey)) {
            uiState = uiState.copy(detailChangeNotesErrorMessage = null)
            return
        }
        if (uiState.detailChangeNotesLoadingId == summary.publishedFileId) return

        uiState = uiState.copy(
            detailChangeNotesLoadingId = summary.publishedFileId,
            detailChangeNotesErrorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getChangeNotes(summary.publishedFileId) }
            }.onSuccess { changeNotes ->
                val current = uiState.selected?.takeIf { selected -> selected.cacheKey() == changeNotesKey }
                val updatedDetails = current?.copy(
                    changeNotes = changeNotes.markdown,
                    changeNotesUrl = changeNotes.url,
                )
                if (updatedDetails != null) {
                    detailsCache[changeNotesKey] = updatedDetails
                }
                uiState = uiState.copy(
                    selected = updatedDetails ?: uiState.selected,
                    detailChangeNotes = uiState.detailChangeNotes + (changeNotesKey to changeNotes),
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = null,
                )
            }.onFailure { error ->
                val stillSelected = uiState.selected?.cacheKey() == changeNotesKey
                uiState = uiState.copy(
                    detailChangeNotesLoadingId = null,
                    detailChangeNotesErrorMessage = if (stillSelected) context.getString(
                        R.string.workshop_change_notes_load_failed,
                        error.message ?: error.javaClass.simpleName,
                    ) else uiState.detailChangeNotesErrorMessage,
                )
            }
        }
    }

    fun loadPreviousWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = -1)
    }

    fun loadNextWorkshopCommentsPage(context: Context) {
        shiftWorkshopCommentsPage(context, delta = 1)
    }

    fun retryWorkshopCommentsPage(context: Context) {
        val details = uiState.selected ?: return
        if (uiState.detailLoadingId != null || uiState.commentLoadingId != null) return
        if (details.commentCount == 0L) {
            uiState = uiState.copy(commentErrorMessage = null)
            return
        }
        if (details.commentThreadContext == null) {
            retryWorkshopCommentContext(context, details)
            return
        }
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = details.commentPage.coerceAtLeast(1),
        )
    }

    private fun retryWorkshopCommentContext(
        context: Context,
        details: WorkshopItemDetails,
    ) {
        val currentService = service ?: return
        val summary = details.summary
        uiState = uiState.copy(
            commentLoadingId = summary.publishedFileId,
            commentErrorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.getDetails(
                        appId = summary.appId,
                        publishedFileId = summary.publishedFileId,
                        fallbackSummary = summary,
                        includeCommunityData = true,
                        includeDependencyData = false,
                    )
                }
            }.onSuccess { loadedDetails ->
                val currentDetails = uiState.selected?.takeIf { current ->
                    current.summary.appId == summary.appId && current.summary.publishedFileId == summary.publishedFileId
                } ?: return@onSuccess
                val refreshedDetails = loadedDetails.retainDetailStateFrom(currentDetails)
                detailsCache[refreshedDetails.cacheKey()] = refreshedDetails
                val shouldLoadComments = refreshedDetails.shouldLoadWorkshopComments()
                uiState = uiState.copy(
                    selected = refreshedDetails,
                    commentLoadingId = if (shouldLoadComments) summary.publishedFileId else null,
                    commentErrorMessage = refreshedDetails.commentUnavailableMessage(context),
                )
                if (shouldLoadComments) {
                    loadWorkshopCommentsPage(
                        context = context,
                        appId = summary.appId,
                        publishedFileId = summary.publishedFileId,
                        page = 1,
                    )
                }
            }.onFailure { error ->
                val stillSelected = uiState.selected?.let { current ->
                    current.summary.appId == summary.appId && current.summary.publishedFileId == summary.publishedFileId
                } == true
                uiState = uiState.copy(
                    commentLoadingId = null,
                    commentErrorMessage = if (stillSelected) {
                        error.message ?: context.getString(R.string.workshop_error_load_comments_failed)
                    } else {
                        uiState.commentErrorMessage
                    },
                )
            }
        }
    }

    private fun shiftWorkshopCommentsPage(context: Context, delta: Int) {
        val details = uiState.selected ?: return
        if (uiState.detailLoadingId != null || uiState.commentLoadingId != null) return
        val targetPage = (details.commentPage + delta).coerceAtLeast(1)
        if (targetPage == details.commentPage) return
        if (delta < 0 && !details.hasPreviousCommentPage) return
        if (delta > 0 && !details.hasNextCommentPage) return
        loadWorkshopCommentsPage(
            context = context,
            appId = details.summary.appId,
            publishedFileId = details.summary.publishedFileId,
            page = targetPage,
        )
    }

    private fun loadWorkshopCommentsPage(
        context: Context,
        appId: UInt,
        publishedFileId: ULong,
        page: Int,
    ) {
        val currentService = service ?: return
        val detailSnapshot = uiState.selected?.takeIf { details ->
            details.summary.appId == appId && details.summary.publishedFileId == publishedFileId
        } ?: return
        val commentUnavailableMessage = detailSnapshot.commentUnavailableMessage(context)
        if (!detailSnapshot.shouldLoadWorkshopComments()) {
            uiState = uiState.copy(
                commentLoadingId = null,
                commentErrorMessage = commentUnavailableMessage,
            )
            return
        }

        uiState = uiState.copy(commentLoadingId = publishedFileId, commentErrorMessage = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentService.getCommentsPage(detailSnapshot, page) }
            }.onSuccess { commentPage ->
                val updatedDetails = uiState.selected?.takeIf { current ->
                    current.summary.appId == appId && current.summary.publishedFileId == publishedFileId
                }?.copy(
                    commentsUrl = commentPage.commentsUrl,
                    commentCount = commentPage.commentCount,
                    commentPage = commentPage.page,
                    commentTotalPages = commentPage.totalPages,
                    hasPreviousCommentPage = commentPage.hasPreviousPage,
                    hasNextCommentPage = commentPage.hasNextPage,
                    comments = commentPage.comments,
                )
                if (updatedDetails != null) {
                    detailsCache[updatedDetails.cacheKey()] = updatedDetails
                }
                uiState = uiState.copy(
                    selected = updatedDetails ?: uiState.selected,
                    commentLoadingId = null,
                    commentErrorMessage = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    commentLoadingId = null,
                    commentErrorMessage = error.message ?: context.getString(R.string.workshop_error_load_comments_failed),
                )
            }
        }
    }

    fun downloadSelected(context: Context) {
        val details = uiState.selected ?: return
        startDownloadAfterDependencyCheck(context, details.summary, details)
    }

    fun showWorkshopSubscriptionSteamLoginRequired(context: Context) {
        Log.w(WORKSHOP_SUBSCRIPTION_LOG_TAG, "workshop subscription action blocked reason=steamLoginRequired")
        uiState = uiState.copy(
            detailSubscriptionLoadingId = null,
            detailSubscriptionMessage = context.getString(R.string.workshop_subscribe_requires_steam_login),
        )
    }

    fun dismissWorkshopSubscribeMessage() {
        uiState = uiState.copy(detailSubscriptionMessage = null)
    }

    private fun refreshDetailSubscriptionStatus(
        appId: UInt,
        publishedFileId: ULong,
    ) {
        val currentService = service ?: return
        if (!currentService.hasSteamAuth()) {
            uiState = uiState.copy(
                steamLoggedIn = false,
                detailSubscriptionStatusId = publishedFileId,
                detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
                subscribedWorkshopIds = uiState.subscribedWorkshopIds - publishedFileId,
            )
            return
        }
        uiState = uiState.copy(
            steamLoggedIn = true,
            detailSubscriptionStatusId = publishedFileId,
            detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Checking,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.isSubscribedToPublishedFile(appId, publishedFileId)
                }
            }.onSuccess { subscribed ->
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "refreshDetailSubscriptionStatus success appId=$appId publishedFileId=$publishedFileId subscribed=$subscribed",
                )
                val nextSubscribedIds = if (subscribed) {
                    uiState.subscribedWorkshopIds + publishedFileId
                } else {
                    uiState.subscribedWorkshopIds - publishedFileId
                }
                uiState = uiState.copy(
                    subscribedWorkshopIds = nextSubscribedIds,
                    detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                        if (subscribed) {
                            WorkshopDetailSubscriptionStatus.Subscribed
                        } else {
                            WorkshopDetailSubscriptionStatus.NotSubscribed
                        }
                    } else {
                        uiState.detailSubscriptionStatus
                    },
                )
            }.onFailure { error ->
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "refreshDetailSubscriptionStatus failed appId=$appId publishedFileId=$publishedFileId reason=steamLoginRequired",
                        error,
                    )
                    uiState = uiState.copy(
                        steamLoggedIn = false,
                        detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                            WorkshopDetailSubscriptionStatus.Unknown
                        } else {
                            uiState.detailSubscriptionStatus
                        },
                        subscribedWorkshopIds = uiState.subscribedWorkshopIds - publishedFileId,
                    )
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "refreshDetailSubscriptionStatus failed appId=$appId publishedFileId=$publishedFileId",
                    error,
                )
                uiState = uiState.copy(
                    detailSubscriptionStatus = if (uiState.detailSubscriptionStatusId == publishedFileId) {
                        WorkshopDetailSubscriptionStatus.Unknown
                    } else {
                        uiState.detailSubscriptionStatus
                    },
                )
            }
        }
    }

    fun subscribeSelected(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val subscriptionStatus = uiState.detailSubscriptionStatusFor(summary.publishedFileId)
        if (uiState.detailSubscriptionLoadingId == summary.publishedFileId ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Checking ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Subscribed ||
            (subscriptionStatus == WorkshopDetailSubscriptionStatus.Unknown && uiState.subscribedWorkshopIds.contains(summary.publishedFileId))
        ) {
            Log.i(
                WORKSHOP_SUBSCRIPTION_LOG_TAG,
                "subscribeSelected skipped appId=${summary.appId} publishedFileId=${summary.publishedFileId} loading=${uiState.detailSubscriptionLoadingId == summary.publishedFileId} status=$subscriptionStatus alreadySubscribed=${uiState.subscribedWorkshopIds.contains(summary.publishedFileId)}",
            )
            return
        }
        if (!currentService.hasSteamAuth()) {
            showWorkshopSubscriptionSteamLoginRequired(context)
            return
        }
        uiState = uiState.copy(
            detailSubscriptionLoadingId = summary.publishedFileId,
            detailSubscriptionMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.subscribeToPublishedFile(summary.appId, summary.publishedFileId)
                }
            }.onSuccess {
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "subscribeSelected success appId=${summary.appId} publishedFileId=${summary.publishedFileId} title=${summary.title}",
                )
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds + summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Subscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_subscribe_success,
                        summary.title.ifBlank { summary.publishedFileId.toString() },
                    ),
                )
            }.onFailure { error ->
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "subscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId} reason=steamLoginRequired",
                        error,
                    )
                    showWorkshopSubscriptionSteamLoginRequired(context)
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "subscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId}",
                    error,
                )
                val errorMessage = if (error is WorkshopSubscriptionVerificationException) {
                    context.getString(R.string.workshop_subscribe_not_confirmed)
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds - summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.NotSubscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_subscribe_failed,
                        errorMessage,
                    ),
                )
            }
        }
    }

    fun unsubscribeSelected(context: Context) {
        val currentService = service ?: return
        val details = uiState.selected ?: return
        val summary = details.summary
        val subscriptionStatus = uiState.detailSubscriptionStatusFor(summary.publishedFileId)
        val subscribed = subscriptionStatus == WorkshopDetailSubscriptionStatus.Subscribed ||
            (subscriptionStatus == WorkshopDetailSubscriptionStatus.Unknown && uiState.subscribedWorkshopIds.contains(summary.publishedFileId))
        if (uiState.detailSubscriptionLoadingId == summary.publishedFileId ||
            subscriptionStatus == WorkshopDetailSubscriptionStatus.Checking ||
            !subscribed
        ) {
            Log.i(
                WORKSHOP_SUBSCRIPTION_LOG_TAG,
                "unsubscribeSelected skipped appId=${summary.appId} publishedFileId=${summary.publishedFileId} loading=${uiState.detailSubscriptionLoadingId == summary.publishedFileId} status=$subscriptionStatus subscribed=$subscribed",
            )
            return
        }
        if (!currentService.hasSteamAuth()) {
            showWorkshopSubscriptionSteamLoginRequired(context)
            return
        }
        uiState = uiState.copy(
            detailSubscriptionLoadingId = summary.publishedFileId,
            detailSubscriptionMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentService.unsubscribeFromPublishedFile(summary.appId, summary.publishedFileId)
                }
            }.onSuccess {
                Log.i(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "unsubscribeSelected success appId=${summary.appId} publishedFileId=${summary.publishedFileId} title=${summary.title}",
                )
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    items = if (activeListMode == WorkshopListMode.Subscriptions) {
                        uiState.items.filterNot { item -> item.publishedFileId == summary.publishedFileId }
                    } else {
                        uiState.items
                    },
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds - summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.NotSubscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_unsubscribe_success,
                        summary.title.ifBlank { summary.publishedFileId.toString() },
                    ),
                )
            }.onFailure { error ->
                if (error is WorkshopSteamLoginRequiredException) {
                    Log.w(
                        WORKSHOP_SUBSCRIPTION_LOG_TAG,
                        "unsubscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId} reason=steamLoginRequired",
                        error,
                    )
                    showWorkshopSubscriptionSteamLoginRequired(context)
                    return@onFailure
                }
                Log.e(
                    WORKSHOP_SUBSCRIPTION_LOG_TAG,
                    "unsubscribeSelected failed appId=${summary.appId} publishedFileId=${summary.publishedFileId}",
                    error,
                )
                val errorMessage = if (error is WorkshopUnsubscriptionVerificationException) {
                    context.getString(R.string.workshop_unsubscribe_not_confirmed)
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                uiState = uiState.copy(
                    detailSubscriptionLoadingId = null,
                    subscribedWorkshopIds = uiState.subscribedWorkshopIds + summary.publishedFileId,
                    detailSubscriptionStatusId = summary.publishedFileId,
                    detailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Subscribed,
                    detailSubscriptionMessage = context.getString(
                        R.string.workshop_unsubscribe_failed,
                        errorMessage,
                    ),
                )
            }
        }
    }

    fun toggleSelectedDetailsTranslation(
        context: Context,
        onOpenBaiduTranslationCredentials: (String) -> Unit = {},
    ) {
        val details = uiState.selected ?: return
        val summary = details.summary
        val translationKey = details.cacheKey()
        if (uiState.detailTranslationModeKey == translationKey) {
            uiState = uiState.copy(
                detailTranslationModeKey = null,
                detailTranslationErrorMessage = null,
            )
            return
        }
        if (uiState.detailTranslations.containsKey(translationKey)) {
            uiState = uiState.copy(
                detailTranslationModeKey = translationKey,
                detailTranslationErrorMessage = null,
            )
            return
        }
        if (uiState.detailTranslationLoadingId == summary.publishedFileId) return

        val originalTitle = summary.title.trim()
        val originalDescription = summary.description.trim()
        if (originalTitle.isBlank() && originalDescription.isBlank()) {
            uiState = uiState.copy(detailTranslationErrorMessage = context.getString(R.string.workshop_translate_no_text))
            return
        }

        val credentials = BaiduTranslationCredentialsRepository(context).getCredentials()
        validateBaiduTranslationCredentials(context, credentials)?.let { message ->
            uiState = uiState.copy(detailTranslationErrorMessage = message)
            onOpenBaiduTranslationCredentials(message)
            return
        }

        val appId = summary.appId
        val publishedFileId = summary.publishedFileId
        uiState = uiState.copy(
            detailTranslationModeKey = translationKey,
            detailTranslationLoadingId = publishedFileId,
            detailTranslationErrorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                val targetLanguage = mapLocaleLanguageToBaiduLanguage(Locale.getDefault()) ?: BAIDU_DEFAULT_TARGET_LANGUAGE
                val reference = buildBaiduModDescriptionReference(
                    modTitle = originalTitle,
                    gameTitle = BAIDU_STS_GAME_TITLE,
                )
                coroutineScope {
                    val translatedTitle = if (originalTitle.isBlank()) {
                        null
                    } else {
                        async {
                            translateWithBaiduCredentials(
                                text = originalTitle,
                                targetLanguage = targetLanguage,
                                credentials = credentials,
                                reference = reference,
                            )
                        }
                    }
                    val translatedDescription = if (originalDescription.isBlank()) {
                        null
                    } else {
                        async {
                            translateWithBaiduCredentials(
                                text = originalDescription,
                                targetLanguage = targetLanguage,
                                credentials = credentials,
                                reference = reference,
                            )
                        }
                    }
                    WorkshopDetailTranslation(
                        title = translatedTitle?.await()?.trim()?.takeIf(String::isNotBlank) ?: summary.title,
                        description = translatedDescription?.await()?.trim()?.takeIf(String::isNotBlank) ?: summary.description,
                    )
                }
            }.onSuccess { translated ->
                val current = uiState.selected?.takeIf { selected ->
                    selected.summary.appId == appId && selected.summary.publishedFileId == publishedFileId
                }
                if (current == null) {
                    uiState = uiState.copy(detailTranslationLoadingId = null)
                    return@onSuccess
                }
                val currentTranslationKey = current.cacheKey()
                uiState = uiState.copy(
                    detailTranslations = uiState.detailTranslations + (currentTranslationKey to translated),
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = null,
                )
            }.onFailure { error ->
                if (error is BaiduTranslationApiException) {
                    val message = error.message ?: context.getString(R.string.workshop_translate_invalid_api_credentials)
                    uiState = uiState.copy(
                        detailTranslationModeKey = null,
                        detailTranslationLoadingId = null,
                        detailTranslationErrorMessage = message,
                    )
                    onOpenBaiduTranslationCredentials(message)
                    return@onFailure
                }
                uiState = uiState.copy(
                    detailTranslationModeKey = null,
                    detailTranslationLoadingId = null,
                    detailTranslationErrorMessage = error.message ?: context.getString(R.string.workshop_translate_failed),
                )
            }
        }
    }

    private suspend fun translateWithBaiduCredentials(
        text: String,
        targetLanguage: String,
        credentials: BaiduTranslationCredentials,
        reference: String,
    ): String = translationClient.translate(
        text = text,
        sourceLanguage = BAIDU_AUTO_DETECT_LANGUAGE,
        targetLanguage = targetLanguage,
        credentials = credentials,
        reference = reference,
    )

    private fun validateBaiduTranslationCredentials(
        context: Context,
        credentials: BaiduTranslationCredentials,
    ): String? = when {
        credentials.appId.isBlank() && credentials.apiKey.isBlank() ->
            context.getString(R.string.workshop_translate_missing_app_id_api_key)

        credentials.appId.isBlank() -> context.getString(R.string.workshop_translate_missing_app_id)
        credentials.apiKey.isBlank() -> context.getString(R.string.workshop_translate_missing_api_key)
        else -> null
    }

    fun pauseDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Pausing,
                    message = context.getString(R.string.workshop_status_pausing),
                    updatedAtMillis = System.currentTimeMillis(),
                    preservePartialDownload = true,
                )
            }
            WorkshopDownloadProcessService.pause(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
        } else {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Paused,
                    message = context.getString(R.string.workshop_status_paused),
                    updatedAtMillis = System.currentTimeMillis(),
                    preservePartialDownload = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.DownloadPaused, context.getString(R.string.workshop_status_paused))
            }
        }
        uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_pausing), downloadInProgress = true)
    }

    fun resumeDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(
            context = context,
            task = task,
            message = context.getString(R.string.main_mod_workshop_action_continue_download),
            preservePartialDownload = true,
        )
    }

    fun retryDownload(context: Context, task: WorkshopDownloadTaskUi) {
        restartDownload(
            context = context,
            task = task,
            message = context.getString(R.string.main_mod_workshop_action_redownload),
            preservePartialDownload = task.preservePartialDownload || task.downloadedBytes > 0L,
        )
    }

    fun cancelDownload(context: Context, task: WorkshopDownloadTaskUi) {
        val details = task.details
        if (task.status.isRunningDownload()) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(status = WorkshopDownloadTaskStatus.Cancelling, message = context.getString(R.string.workshop_status_canceling), updatedAtMillis = System.currentTimeMillis())
            }
            WorkshopDownloadProcessService.cancel(context, details.summary.appId, details.summary.publishedFileId, createDownloadResultReceiver(context.applicationContext, details.summary))
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_canceling), downloadInProgress = true)
            return
        }
        WorkshopDownloadCenterStore.removeInMemory(task.publishedFileId)
        uiState = uiState.copy(
            downloadStatus = context.getString(R.string.workshop_status_cancelled),
            downloadInProgress = false,
            installedMods = uiState.installedMods.filterNot {
                it.appId == details.summary.appId && it.publishedFileId == details.summary.publishedFileId
            },
        )
        persistDownloadTaskAsync {
            WorkshopDownloadTaskStore(context).removeAndMarkDeleted(task.publishedFileId)
            metadataStore?.remove(details.summary.appId, details.summary.publishedFileId)
            File(context.filesDir, "workshop/${details.summary.appId}/${details.summary.publishedFileId}").deleteRecursively()
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    fun download(context: Context, item: WorkshopItemSummary) {
        val existingTask = WorkshopDownloadCenterStore.find(item.publishedFileId)
        if (blockBlockedWorkshopDownload(context, item, existingTask)) return
        if (existingTask?.status == WorkshopDownloadTaskStatus.Paused) {
            resumeDownload(context, existingTask)
            return
        }
        if (existingTask?.status == WorkshopDownloadTaskStatus.Failed || existingTask?.status == WorkshopDownloadTaskStatus.Cancelled) {
            retryDownload(context, existingTask)
            return
        }
        val state = resolveWorkshopModDownloadState(
            item = item,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (!state.canStartDownload) return
        val selectedDetails = findCachedDetails(item.appId, item.publishedFileId)
        if (selectedDetails != null) {
            startDownloadAfterDependencyCheck(context, item, selectedDetails)
            return
        }
        val currentService = service ?: return
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_dependencies))
            runCatching {
                withContext(Dispatchers.IO) { currentService.getDetails(item.appId, item.publishedFileId, item) }
            }.onSuccess { details ->
                detailsCache[details.cacheKey()] = details
                uiState = uiState.copy(selected = details)
                startDownloadAfterDependencyCheck(context, details.summary, details)
            }.onFailure { error ->
                uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_dependency_check_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun confirmPendingDependencyDownload(context: Context) {
        val pending = uiState.pendingDependencyDownload ?: return
        uiState = uiState.copy(pendingDependencyDownload = null)
        if (blockBlockedWorkshopDownload(context, pending.details.summary, WorkshopDownloadCenterStore.find(pending.details.summary.publishedFileId))) return
        pending.missingDependencies.forEach { dependency -> startDownload(context, dependency) }
        startDownload(context, pending.details.summary, pending.details)
    }

    fun downloadPendingCurrentOnly(context: Context) {
        val pending = uiState.pendingDependencyDownload ?: return
        uiState = uiState.copy(pendingDependencyDownload = null)
        startDownload(context, pending.details.summary, pending.details)
    }

    fun dismissPendingDependencyDownload() {
        uiState = uiState.copy(pendingDependencyDownload = null)
    }

    private fun startDownloadAfterDependencyCheck(
        context: Context,
        item: WorkshopItemSummary,
        details: WorkshopItemDetails,
    ) {
        if (blockBlockedWorkshopDownload(context, item, WorkshopDownloadCenterStore.find(item.publishedFileId))) return
        val missingDependencies = findMissingWorkshopDependencies(
            dependencies = details.dependencies,
            installedMods = uiState.installedMods,
            downloadTasks = WorkshopDownloadCenterStore.tasks,
        )
        if (missingDependencies.isNotEmpty()) {
            uiState = uiState.copy(
                pendingDependencyDownload = WorkshopPendingDependencyDownload(
                    details = details,
                    missingDependencies = missingDependencies,
                ),
            )
            return
        }
        startDownload(context, item, details)
    }

    private fun startDownload(
        context: Context,
        summary: WorkshopItemSummary,
        details: WorkshopItemDetails? = null,
    ) {
        if (blockBlockedWorkshopDownload(context, summary, WorkshopDownloadCenterStore.find(summary.publishedFileId))) return
        val alreadyRunning = WorkshopDownloadCenterStore.tasks.any { it.status.isRunningDownload() }
        val queuedDetails = details ?: WorkshopItemDetails(summary = summary)
        val queuedTask = WorkshopDownloadTaskUi(
            publishedFileId = summary.publishedFileId,
            title = summary.title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = if (alreadyRunning) context.getString(R.string.workshop_status_added_to_queue) else context.getString(R.string.workshop_download_task_message_waiting),
            details = queuedDetails,
            previewUrl = summary.previewUrl,
            description = summary.description,
            authorName = summary.authorName,
            fileSizeBytes = summary.fileSizeBytes,
            totalBytes = summary.fileSizeBytes.takeIf { it > 0L },
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
            downloadLog = "",
        )
        val queuedRecord = WorkshopInstalledModRecord(
            appId = summary.appId,
            publishedFileId = summary.publishedFileId,
            title = summary.title,
                description = summary.description,
                previewUrl = summary.previewUrl,
                versionText = summary.updatedAtMillis.toString(),
                updatedAtMillis = summary.updatedAtMillis,
                installedAtMillis = System.currentTimeMillis(),
            localJarPath = "",
            cardState = WorkshopModCardState.Downloading,
            statusText = context.getString(R.string.workshop_download_task_message_waiting),
            dependencies = queuedDetails.dependencies,
        )
        WorkshopDownloadCenterStore.upsertInMemory(queuedTask)
        uiState = uiState.copy(
            downloadStatus = if (alreadyRunning) {
                context.getString(R.string.workshop_status_added_to_queue_format, summary.title)
            } else {
                context.getString(R.string.workshop_status_starting_download_format, summary.title)
            },
            downloadInProgress = true,
            installedMods = listOf(queuedRecord) + uiState.installedMods.filterNot {
                it.appId == queuedRecord.appId && it.publishedFileId == queuedRecord.publishedFileId
            },
        )
        persistDownloadTaskAsync {
            WorkshopDownloadCenterStore.persistUpsert(queuedTask)
            metadataStore?.upsert(queuedRecord)
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    private fun restartDownload(context: Context, task: WorkshopDownloadTaskUi, message: String) {
        restartDownload(context, task, message, preservePartialDownload = false)
    }

    private fun restartDownload(
        context: Context,
        task: WorkshopDownloadTaskUi,
        message: String,
        preservePartialDownload: Boolean,
    ) {
        val details = task.details
        if (blockBlockedWorkshopDownload(context, details.summary, task)) return
        val queuedTask = task.copy(
            status = WorkshopDownloadTaskStatus.Queued,
            message = if (WorkshopDownloadCenterStore.hasRunningTask()) context.getString(R.string.workshop_status_added_to_queue) else message,
            updatedAtMillis = System.currentTimeMillis(),
            progressPercent = if (preservePartialDownload) task.progressPercent else null,
            downloadedBytes = if (preservePartialDownload) task.downloadedBytes else 0L,
            completedFiles = if (preservePartialDownload) task.completedFiles else null,
            completedChunks = if (preservePartialDownload) task.completedChunks else null,
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
            downloadLog = if (preservePartialDownload) task.downloadLog else "",
            preservePartialDownload = preservePartialDownload,
        )
        WorkshopDownloadCenterStore.upsertInMemory(queuedTask)
        uiState = uiState.copy(downloadInProgress = true)
        persistDownloadTaskAsync {
            WorkshopDownloadCenterStore.persistUpsert(queuedTask)
            metadataStore?.updateState(details.summary.appId, details.summary.publishedFileId, WorkshopModCardState.Downloading, context.getString(R.string.workshop_download_task_message_waiting))
            WorkshopDownloadProcessService.startNextQueued(context)
        }
    }

    private fun blockBlockedWorkshopDownload(
        context: Context,
        summary: WorkshopItemSummary,
        task: WorkshopDownloadTaskUi?,
    ): Boolean {
        if (!WorkshopDownloadBlocklist.isBlocked(summary)) return false
        val message = context.getString(
            R.string.workshop_status_download_blocked,
            summary.title.ifBlank { summary.publishedFileId.toString() },
        )
        val blockedTaskMessage = context.getString(R.string.workshop_download_task_message_blocked)
        if (task != null) {
            updateDownloadTaskAsync(task.publishedFileId) {
                it.copy(
                    status = WorkshopDownloadTaskStatus.Cancelled,
                    message = blockedTaskMessage,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                metadataStore?.updateState(
                    summary.appId,
                    summary.publishedFileId,
                    WorkshopModCardState.DownloadFailed,
                    blockedTaskMessage,
                )
            }
        }
        uiState = uiState.copy(
            downloadStatus = message,
            downloadInProgress = WorkshopDownloadCenterStore.tasks.any { it.status.isActiveDownload() },
        )
        return true
    }

    private fun createDownloadResultReceiver(context: Context, summary: WorkshopItemSummary): ResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            val message = resultData.getString(WorkshopDownloadProcessService.EXTRA_MESSAGE).orEmpty()
            val status = resultData.getString(WorkshopDownloadProcessService.EXTRA_TASK_STATUS)?.toTaskStatus()
            if (status != null || message.isNotBlank()) {
                updateDownloadTaskAsync(summary.publishedFileId) {
                    it.copy(
                        status = status ?: it.status,
                        message = message.ifBlank { it.message },
                        updatedAtMillis = System.currentTimeMillis(),
                        progressPercent = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_PROGRESS_PERCENT) ?: it.progressPercent,
                        downloadedBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_WRITTEN_BYTES) ?: it.downloadedBytes,
                        totalBytes = resultData.optionalLong(WorkshopDownloadProcessService.EXTRA_TOTAL_BYTES) ?: it.totalBytes,
                        completedFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_FILES) ?: it.completedFiles,
                        totalFiles = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_FILES) ?: it.totalFiles,
                        completedChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_COMPLETED_CHUNKS) ?: it.completedChunks,
                        totalChunks = resultData.optionalInt(WorkshopDownloadProcessService.EXTRA_TOTAL_CHUNKS) ?: it.totalChunks,
                        errorClass = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_CLASS).orEmpty().ifBlank { it.errorClass },
                        errorMessage = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_MESSAGE).orEmpty().ifBlank { it.errorMessage },
                        errorStackTrace = resultData.getString(WorkshopDownloadProcessService.EXTRA_ERROR_STACKTRACE).orEmpty().ifBlank { it.errorStackTrace },
                    )
                }
            }
            when (resultCode) {
                WorkshopDownloadProcessService.RESULT_PROGRESS -> {
                    if (message.isNotBlank()) uiState = uiState.copy(downloadStatus = message)
                }
                WorkshopDownloadProcessService.RESULT_COMPLETED -> {
                    updateDownloadTaskAsync(summary.publishedFileId) {
                        it.copy(
                            status = WorkshopDownloadTaskStatus.Completed,
                            message = message.ifBlank { context.getString(R.string.workshop_status_download_completed) },
                            progressPercent = 100,
                            downloadedBytes = (it.totalBytes ?: it.downloadedBytes).coerceAtLeast(it.downloadedBytes),
                            completedFiles = it.totalFiles ?: it.completedFiles,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_download_completed) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
                WorkshopDownloadProcessService.RESULT_FAILURE -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_download_failed) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
                WorkshopDownloadProcessService.RESULT_PAUSED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_paused) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
                WorkshopDownloadProcessService.RESULT_CANCELLED -> {
                    uiState = uiState.copy(
                        downloadStatus = message.ifBlank { context.getString(R.string.workshop_status_cancelled) },
                        downloadInProgress = false,
                    )
                    refreshDownloadState(context)
                }
            }
        }
    }

    fun checkUpdates(context: Context) {
        viewModelScope.launch {
            uiState = uiState.copy(downloadStatus = context.getString(R.string.workshop_status_checking_updates), updateChecking = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = WorkshopUpdateChecker(context).checkInstalledMods()
                    Pair(report, metadataStore?.list().orEmpty())
                }
            }
                .onSuccess { (report, installedMods) ->
                    val results = report.results
                    val updateCount = results.count { it.hasUpdate }
                    uiState = uiState.copy(
                        updateResults = results,
                        installedMods = installedMods,
                        updateChecking = false,
                        downloadStatus = if (updateCount > 0) {
                            buildString {
                                append(context.getString(R.string.workshop_status_updates_found, updateCount))
                                if (report.failedCount > 0) append(context.getString(R.string.workshop_status_update_failed_suffix, report.failedCount))
                            }
                        } else if (report.failedCount > 0) {
                            context.getString(R.string.workshop_status_update_check_completed_failed, report.failedCount)
                        } else {
                            context.getString(R.string.workshop_status_all_up_to_date)
                        },
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        updateChecking = false,
                        downloadStatus = context.getString(R.string.workshop_status_update_check_failed, error.message ?: error.javaClass.simpleName),
                    )
                }
        }
    }
}

internal data class WorkshopUiState(
    val browseLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextPage: Int = 1,
    val hasMorePages: Boolean = true,
    val listMode: WorkshopListMode = WorkshopListMode.Browse,
    val detailLoadingId: ULong? = null,
    val downloadInProgress: Boolean = false,
    val updateChecking: Boolean = false,
    val steamLoggedIn: Boolean = false,
    val items: List<WorkshopItemSummary> = emptyList(),
    val selected: WorkshopItemDetails? = null,
    val downloadStatus: String = "",
    val installedMods: List<WorkshopInstalledModRecord> = emptyList(),
    val updateResults: List<WorkshopUpdateCheckResult> = emptyList(),
    val pendingDependencyDownload: WorkshopPendingDependencyDownload? = null,
    val commentLoadingId: ULong? = null,
    val commentErrorMessage: String? = null,
    val detailTranslationModeKey: String? = null,
    val detailTranslations: Map<String, WorkshopDetailTranslation> = emptyMap(),
    val detailTranslationLoadingId: ULong? = null,
    val detailTranslationErrorMessage: String? = null,
    val detailChangeNotes: Map<String, WorkshopChangeNotes> = emptyMap(),
    val detailChangeNotesLoadingId: ULong? = null,
    val detailChangeNotesErrorMessage: String? = null,
    val detailSubscriptionLoadingId: ULong? = null,
    val detailSubscriptionStatusId: ULong? = null,
    val detailSubscriptionStatus: WorkshopDetailSubscriptionStatus = WorkshopDetailSubscriptionStatus.Unknown,
    val subscribedWorkshopIds: Set<ULong> = emptySet(),
    val detailSubscriptionMessage: String? = null,
    val errorMessage: String? = null,
) {
    fun detailSubscriptionStatusFor(publishedFileId: ULong): WorkshopDetailSubscriptionStatus =
        if (detailSubscriptionStatusId == publishedFileId) detailSubscriptionStatus else WorkshopDetailSubscriptionStatus.Unknown

    companion object {
        const val PAGE_SIZE = 30
    }
}

internal enum class WorkshopListMode {
    Browse,
    Subscriptions,
}

internal enum class WorkshopDetailSubscriptionStatus {
    Unknown,
    Checking,
    NotSubscribed,
    Subscribed,
}

internal data class WorkshopPendingDependencyDownload(
    val details: WorkshopItemDetails,
    val missingDependencies: List<WorkshopItemSummary>,
)

internal data class WorkshopDetailTranslation(
    val title: String,
    val description: String,
)

private fun WorkshopItemDetails.shouldLoadWorkshopComments(): Boolean =
    commentThreadContext != null && commentCount != 0L

private fun WorkshopItemDetails.shouldLoadInitialWorkshopComments(): Boolean =
    shouldLoadWorkshopComments() && comments.isEmpty()

private fun WorkshopItemDetails.canReuseForDetailOpen(fallbackSummary: WorkshopItemSummary?): Boolean {
    if (!hasReusableCommunityData()) return false
    if (fallbackSummary == null || fallbackSummary.appId != summary.appId || fallbackSummary.publishedFileId != summary.publishedFileId) {
        return true
    }
    return fallbackSummary.updatedAtMillis <= 0L ||
        summary.updatedAtMillis <= 0L ||
        fallbackSummary.updatedAtMillis <= summary.updatedAtMillis
}

private fun WorkshopItemDetails.hasReusableCommunityData(): Boolean =
    commentThreadContext != null || commentCount != null || comments.isNotEmpty()

private fun WorkshopItemDetails.mergeCachedCommunityData(cached: WorkshopItemDetails?): WorkshopItemDetails {
    if (cached == null || cached.cacheKey() != cacheKey()) return this
    val communityMissing = commentThreadContext == null && commentCount == null
    val samePublishedVersion = summary.updatedAtMillis <= 0L ||
        cached.summary.updatedAtMillis <= 0L ||
        summary.updatedAtMillis == cached.summary.updatedAtMillis
    val useCachedDescription = cached.summary.description.length > summary.description.length &&
        samePublishedVersion &&
        (communityMissing || cached.summary.description.contains(summary.description.trim()))
    val useCachedComments = communityMissing && cached.commentThreadContext != null
    val useCachedDependencies = communityMissing && dependencies.isEmpty() && cached.dependencies.isNotEmpty()
    return copy(
        summary = summary.copy(
            description = if (useCachedDescription) cached.summary.description else summary.description,
            authorName = summary.authorName.ifBlank { cached.summary.authorName },
        ),
        fullDescriptionUnavailable = if (useCachedDescription) {
            cached.fullDescriptionUnavailable
        } else {
            fullDescriptionUnavailable
        },
        changeNotes = changeNotes.ifBlank { cached.changeNotes },
        changeNotesUrl = changeNotesUrl.ifBlank { cached.changeNotesUrl },
        dependencies = if (useCachedDependencies) cached.dependencies else dependencies,
        commentThreadContext = if (useCachedComments) cached.commentThreadContext else commentThreadContext,
        commentCount = if (useCachedComments) cached.commentCount else commentCount,
        commentPage = if (useCachedComments && cached.comments.isNotEmpty()) cached.commentPage else commentPage,
        commentTotalPages = if (useCachedComments) cached.commentTotalPages else commentTotalPages,
        hasPreviousCommentPage = if (useCachedComments && cached.comments.isNotEmpty()) {
            cached.hasPreviousCommentPage
        } else {
            hasPreviousCommentPage
        },
        hasNextCommentPage = if (useCachedComments) cached.hasNextCommentPage else hasNextCommentPage,
        comments = if (useCachedComments && cached.comments.isNotEmpty()) cached.comments else comments,
    )
}

private fun WorkshopItemDetails.retainDetailStateFrom(current: WorkshopItemDetails): WorkshopItemDetails {
    if (current.cacheKey() != cacheKey()) return this
    val merged = mergeCachedCommunityData(current)
    return merged.copy(
        changeNotes = merged.changeNotes.ifBlank { current.changeNotes },
        changeNotesUrl = merged.changeNotesUrl.ifBlank { current.changeNotesUrl },
        dependencies = if (merged.dependencies.isEmpty()) current.dependencies else merged.dependencies,
    )
}

private fun WorkshopItemDetails.cacheKey(): String = detailsCacheKey(summary.appId, summary.publishedFileId)

private fun detailsCacheKey(appId: UInt, publishedFileId: ULong): String = "$appId:$publishedFileId"

private fun WorkshopInstalledModRecord.toWorkshopItemSummary(): WorkshopItemSummary = WorkshopItemSummary(
    publishedFileId = publishedFileId,
    appId = appId,
    title = title,
    previewUrl = previewUrl,
    description = description,
    updatedAtMillis = updatedAtMillis,
)

private fun WorkshopItemDetails.commentUnavailableMessage(context: Context): String? = when {
    commentCount == 0L -> null
    commentThreadContext == null -> context.getString(R.string.workshop_comments_unavailable)
    else -> null
}

private fun String.toTaskStatus(): WorkshopDownloadTaskStatus? = when (this) {
    "Queued" -> WorkshopDownloadTaskStatus.Queued
    "Resolving" -> WorkshopDownloadTaskStatus.Resolving
    "Downloading" -> WorkshopDownloadTaskStatus.Downloading
    "Pausing" -> WorkshopDownloadTaskStatus.Pausing
    "Cancelling" -> WorkshopDownloadTaskStatus.Cancelling
    "Paused" -> WorkshopDownloadTaskStatus.Paused
    "Completed" -> WorkshopDownloadTaskStatus.Completed
    "Failed" -> WorkshopDownloadTaskStatus.Failed
    "Cancelled" -> WorkshopDownloadTaskStatus.Cancelled
    else -> null
}

private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

private const val BAIDU_AUTO_DETECT_LANGUAGE = "auto"
private const val BAIDU_DEFAULT_TARGET_LANGUAGE = "zh"
private const val BAIDU_STS_GAME_TITLE = "Slay the Spire"
private const val WORKSHOP_SUBSCRIPTION_LOG_TAG = "WorkshopSubscribe"
