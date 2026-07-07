package io.stamethyst.ui.workshop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopChangeNotes
import io.stamethyst.backend.workshop.WorkshopComment
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LoadingSkeletonBlock
import io.stamethyst.ui.LoadingSkeletonStyle
import io.stamethyst.ui.RemoteBitmapCacheStore
import io.stamethyst.ui.SimpleMarkdownCard
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.Close
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.rememberLoadingSkeletonStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorkshopDetailScreen(
    appId: UInt,
    publishedFileId: ULong,
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenBaiduTranslationCredentials: (String) -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(appId, publishedFileId) {
        viewModel.load(context.applicationContext)
        viewModel.loadDetails(context.applicationContext, appId, publishedFileId)
    }

    LaunchedEffect(state.downloadInProgress) {
        if (!state.downloadInProgress) return@LaunchedEffect
        while (true) {
            delay(1000L)
            viewModel.refreshDownloadTaskState(context.applicationContext)
        }
    }

    val selectedDetails = state.selected?.takeIf { it.summary.publishedFileId == publishedFileId }
    val isTranslatingDetails = state.detailTranslationLoadingId == publishedFileId
    val selectedDetailsKey = selectedDetails?.summary?.let { summary ->
        "${summary.appId}:${summary.publishedFileId}"
    }
    val selectedTranslationKey = selectedDetailsKey
    val isTranslationMode = selectedTranslationKey != null && state.detailTranslationModeKey == selectedTranslationKey
    val selectedTranslation = selectedTranslationKey?.let { key -> state.detailTranslations[key] }
    val selectedCommentTranslationKey = selectedDetails?.commentTranslationCacheKey()
    val isTranslatingComments = isTranslationMode &&
        selectedCommentTranslationKey != null &&
        state.commentTranslationLoadingKey == selectedCommentTranslationKey
    val selectedChangeNotes = selectedDetailsKey?.let { key -> state.detailChangeNotes[key] }
        ?: selectedDetails?.takeIf { it.changeNotes.isNotBlank() }?.let { details ->
            WorkshopChangeNotes(
                publishedFileId = details.summary.publishedFileId,
                markdown = details.changeNotes,
                latestMarkdown = details.changeNotes,
                url = details.changeNotesUrl,
            )
        }
    val isLoadingChangeNotes = state.detailChangeNotesLoadingId == publishedFileId
    val canTranslateDetails = selectedDetails?.let { details ->
        details.summary.title.isNotBlank() ||
            details.summary.description.isNotBlank() ||
            details.comments.any { comment -> comment.content.isNotBlank() }
    } == true
    var showChangeNotesDialog by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    var showSubscribeConfirmDialog by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    var showUnsubscribeConfirmDialog by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    var dontRemindSubscribeWarning by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    var fullscreenGalleryStartIndex by rememberSaveable(publishedFileId.toString()) { mutableIntStateOf(-1) }
    var fullscreenGalleryVisible by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    val primaryContentState = when {
        state.detailLoadingId == publishedFileId && selectedDetails == null -> DetailPrimaryContentState.Loading
        state.errorMessage != null && selectedDetails == null -> DetailPrimaryContentState.Error
        selectedDetails != null -> DetailPrimaryContentState.Content
        else -> DetailPrimaryContentState.Loading
    }
    val selectedGalleryImageUrls = remember(selectedDetails?.previewImageUrls) {
        selectedDetails?.previewImageUrls.orEmpty()
            .filter(String::isNotBlank)
            .distinct()
    }
    val galleryBitmapCache = remember { mutableStateMapOf<String, Bitmap>() }
    LaunchedEffect(selectedGalleryImageUrls) {
        if (fullscreenGalleryStartIndex > selectedGalleryImageUrls.lastIndex) {
            fullscreenGalleryVisible = false
            fullscreenGalleryStartIndex = -1
        }
        galleryBitmapCache.keys.retainAll(selectedGalleryImageUrls.toSet())
    }
    LaunchedEffect(fullscreenGalleryVisible, fullscreenGalleryStartIndex) {
        if (!fullscreenGalleryVisible && fullscreenGalleryStartIndex >= 0) {
            delay(GalleryFullscreenExitAnimationMs.toLong())
            if (!fullscreenGalleryVisible) {
                fullscreenGalleryStartIndex = -1
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        val originalTitle = state.selected
                            ?.takeIf { it.summary.publishedFileId == publishedFileId }
                            ?.summary
                            ?.title
                            ?.ifBlank { null }
                        val title = if (isTranslationMode) {
                            selectedTranslation?.title?.ifBlank { null } ?: originalTitle
                        } else {
                            originalTitle
                        }
                        Column {
                            Text(
                                text = title ?: stringResource(R.string.workshop_detail_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.workshop_detail_subtitle_format, publishedFileId.toString()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.ArrowBack,
                                contentDescription = stringResource(R.string.common_content_desc_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.toggleSelectedDetailsTranslation(
                                    context = context.applicationContext,
                                    onOpenBaiduTranslationCredentials = onOpenBaiduTranslationCredentials,
                                )
                            },
                            enabled = canTranslateDetails && !isTranslatingDetails,
                        ) {
                            if (isTranslatingDetails) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    painter = painterResource(
                                        if (isTranslationMode) R.drawable.ic_original_text else R.drawable.ic_translate,
                                    ),
                                    contentDescription = stringResource(
                                        if (isTranslationMode) {
                                            R.string.workshop_translate_show_original_action
                                        } else {
                                            R.string.workshop_translate_action
                                        },
                                    ),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "workshop-detail-primary") {
                    AnimatedContent(
                        targetState = primaryContentState,
                        transitionSpec = {
                            workshopDetailPrimaryContentTransition()
                        },
                        label = "workshop-detail-primary-content",
                    ) { targetState ->
                        when (targetState) {
                            DetailPrimaryContentState.Loading -> WorkshopDetailPrimaryLoadingSkeleton()
                            DetailPrimaryContentState.Error -> DetailErrorCard(
                                message = state.errorMessage.orEmpty(),
                                onRetry = { viewModel.loadDetails(context.applicationContext, appId, publishedFileId) },
                            )
                            DetailPrimaryContentState.Content -> selectedDetails?.let { details ->
                                val subscriptionStatus = state.detailSubscriptionStatusFor(details.summary.publishedFileId)
                                val detailSubscribed = when (subscriptionStatus) {
                                    WorkshopDetailSubscriptionStatus.Subscribed -> true
                                    WorkshopDetailSubscriptionStatus.NotSubscribed,
                                    WorkshopDetailSubscriptionStatus.Checking -> false
                                    WorkshopDetailSubscriptionStatus.Unknown ->
                                        state.subscribedWorkshopIds.contains(details.summary.publishedFileId)
                                }
                                DetailModCard(
                                    details = details,
                                    downloadState = resolveWorkshopModDownloadState(
                                        item = details.summary,
                                        installedMods = state.installedMods,
                                        downloadTasks = WorkshopDownloadCenterStore.tasks,
                                        preparingDownloadIds = state.preparingDownloadIds,
                                    ),
                                    subscriptionLoading = state.detailSubscriptionLoadingId == details.summary.publishedFileId ||
                                        subscriptionStatus == WorkshopDetailSubscriptionStatus.Checking,
                                    subscribed = detailSubscribed,
                                    onDownload = {
                                        requestNotificationPermissionIfNeeded()
                                        viewModel.downloadSelected(context.applicationContext)
                                    },
                                    onViewChangeNotes = {
                                        showChangeNotesDialog = true
                                        viewModel.loadSelectedChangeNotes(context.applicationContext)
                                    },
                                    onSubscriptionClick = {
                                        if (!state.steamLoggedIn) {
                                            viewModel.showWorkshopSubscriptionSteamLoginRequired(context.applicationContext)
                                        } else if (detailSubscribed) {
                                            showUnsubscribeConfirmDialog = true
                                        } else if (LauncherPreferences.isWorkshopSubscribeWarningDismissed(context.applicationContext)) {
                                            viewModel.subscribeSelected(context.applicationContext)
                                        } else {
                                            dontRemindSubscribeWarning = false
                                            showSubscribeConfirmDialog = true
                                        }
                                    },
                                )
                            } ?: WorkshopDetailPrimaryLoadingSkeleton()
                        }
                    }
                }

                if (primaryContentState == DetailPrimaryContentState.Loading) {
                    item(key = "workshop-detail-preview-gallery") {
                        WorkshopDetailGallerySkeletonCard(skeletonStyle = rememberLoadingSkeletonStyle("workshop_detail_gallery_skeleton"))
                    }
                    item(key = "workshop-detail-description") {
                        WorkshopDetailDescriptionSkeletonCard(skeletonStyle = rememberLoadingSkeletonStyle("workshop_detail_description_skeleton"))
                    }
                    item(key = "workshop-detail-comments") {
                        WorkshopDetailCommentsSkeletonCard(skeletonStyle = rememberLoadingSkeletonStyle("workshop_detail_comments_skeleton"))
                    }
                }

                selectedDetails?.let { details ->
                    val visibleDependencyItems = filterVisibleWorkshopDetailDependencies(details.dependencies)
                    val dependencies = resolveWorkshopDependencyUiStates(
                        dependencies = visibleDependencyItems,
                        installedMods = state.installedMods,
                        downloadTasks = WorkshopDownloadCenterStore.tasks,
                        preparingDownloadIds = state.preparingDownloadIds,
                    )
                    if (selectedGalleryImageUrls.isNotEmpty()) {
                        item(key = "workshop-detail-preview-gallery") {
                            DetailPreviewGalleryCard(
                                title = details.summary.title,
                                imageUrls = selectedGalleryImageUrls,
                                loadedBitmaps = galleryBitmapCache,
                                isFullscreenOpen = fullscreenGalleryStartIndex >= 0,
                                onImageLoaded = { url, bitmap ->
                                    galleryBitmapCache[url] = bitmap
                                },
                                onOpenFullscreen = { page ->
                                    fullscreenGalleryStartIndex = page.coerceIn(0, selectedGalleryImageUrls.lastIndex)
                                    fullscreenGalleryVisible = true
                                },
                            )
                        }
                    }
                    if (dependencies.isNotEmpty()) {
                        item(key = "workshop-detail-dependencies") {
                            DependencyCard(
                                dependencies = dependencies,
                                onDownloadDependency = { dependency ->
                                    requestNotificationPermissionIfNeeded()
                                    viewModel.download(context.applicationContext, dependency)
                                },
                                onOpenDependency = onOpenDetails,
                            )
                        }
                    }
                    item(key = "workshop-detail-description") {
                        DetailDescriptionCard(
                            publishedFileId = details.summary.publishedFileId,
                            text = if (isTranslationMode) {
                                selectedTranslation?.description ?: details.summary.description
                            } else {
                                details.summary.description
                            },
                            fullDescriptionUnavailable = details.fullDescriptionUnavailable,
                            isTranslating = isTranslatingDetails,
                            isReloading = state.detailLoadingId == details.summary.publishedFileId,
                            translationErrorMessage = state.detailTranslationErrorMessage,
                            onRetryFullDescription = {
                                viewModel.retryDetailsLoad(
                                    context = context.applicationContext,
                                    appId = appId,
                                    publishedFileId = publishedFileId,
                                )
                            },
                        )
                    }
                    item(key = "workshop-detail-comments") {
                        DetailCommentsCard(
                            details = details,
                            isLoading = state.commentLoadingId == publishedFileId,
                            errorMessage = state.commentErrorMessage,
                            isTranslationMode = isTranslationMode,
                            isTranslatingComments = isTranslatingComments,
                            translationErrorMessage = state.commentTranslationErrorMessage.takeIf { isTranslationMode },
                            onRetry = { viewModel.retryWorkshopCommentsPage(context.applicationContext) },
                            onPreviousPage = { viewModel.loadPreviousWorkshopCommentsPage(context.applicationContext) },
                            onNextPage = { viewModel.loadNextWorkshopCommentsPage(context.applicationContext) },
                        )
                    }
                }
            }
        }

        state.pendingDependencyDownload?.let { pending ->
            MissingWorkshopDependenciesDialog(
                modTitle = pending.details.summary.title,
                missingDependencies = pending.missingDependencies,
                onDismiss = { viewModel.dismissPendingDependencyDownload() },
                onDownloadCurrentOnly = {
                    requestNotificationPermissionIfNeeded()
                    viewModel.downloadPendingCurrentOnly(context.applicationContext)
                },
                onConfirm = {
                    requestNotificationPermissionIfNeeded()
                    viewModel.confirmPendingDependencyDownload(context.applicationContext)
                },
            )
        }

        if (showChangeNotesDialog && selectedDetails != null) {
            WorkshopChangeNotesDialog(
                title = selectedDetails.summary.title.ifBlank { stringResource(R.string.workshop_change_notes_title) },
                changeNotes = selectedChangeNotes,
                isLoading = isLoadingChangeNotes,
                errorMessage = state.detailChangeNotesErrorMessage,
                onRetry = { viewModel.loadSelectedChangeNotes(context.applicationContext) },
                onDismiss = { showChangeNotesDialog = false },
            )
        }

        if (showSubscribeConfirmDialog && selectedDetails != null) {
            WorkshopSubscribeConfirmDialog(
                modTitle = selectedDetails.summary.title.ifBlank { selectedDetails.summary.publishedFileId.toString() },
                dontRemind = dontRemindSubscribeWarning,
                onDontRemindChange = { dontRemindSubscribeWarning = it },
                onDismiss = { showSubscribeConfirmDialog = false },
                onConfirm = {
                    if (dontRemindSubscribeWarning) {
                        LauncherPreferences.setWorkshopSubscribeWarningDismissed(context.applicationContext, true)
                    }
                    showSubscribeConfirmDialog = false
                    viewModel.subscribeSelected(context.applicationContext)
                },
            )
        }

        if (showUnsubscribeConfirmDialog && selectedDetails != null) {
            WorkshopUnsubscribeConfirmDialog(
                modTitle = selectedDetails.summary.title.ifBlank { selectedDetails.summary.publishedFileId.toString() },
                onDismiss = { showUnsubscribeConfirmDialog = false },
                onConfirm = {
                    showUnsubscribeConfirmDialog = false
                    viewModel.unsubscribeSelected(context.applicationContext)
                },
            )
        }

        state.detailSubscriptionMessage?.let { message ->
            WorkshopSubscribeMessageDialog(
                message = message,
                onDismiss = { viewModel.dismissWorkshopSubscribeMessage() },
            )
        }

        if (fullscreenGalleryStartIndex >= 0 && selectedGalleryImageUrls.isNotEmpty()) {
            AnimatedVisibility(
                visible = fullscreenGalleryVisible,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
                enter = fadeIn(animationSpec = tween(GalleryFullscreenEnterAnimationMs)) +
                    scaleIn(
                        animationSpec = tween(GalleryFullscreenEnterAnimationMs),
                        initialScale = 0.94f,
                    ),
                exit = fadeOut(animationSpec = tween(GalleryFullscreenExitAnimationMs)) +
                    scaleOut(
                        animationSpec = tween(GalleryFullscreenExitAnimationMs),
                        targetScale = 0.96f,
                    ),
                label = "workshop-gallery-fullscreen-visibility",
            ) {
                WorkshopGalleryFullscreenBrowser(
                    title = selectedDetails?.summary?.title.orEmpty(),
                    imageUrls = selectedGalleryImageUrls,
                    loadedBitmaps = galleryBitmapCache,
                    initialPage = fullscreenGalleryStartIndex,
                    modifier = Modifier.fillMaxSize(),
                    onImageLoaded = { url, bitmap ->
                        galleryBitmapCache[url] = bitmap
                    },
                    onDismiss = { fullscreenGalleryVisible = false },
                )
            }
        }
    }
}

@Composable
private fun DetailPreviewGalleryCard(
    modifier: Modifier = Modifier,
    title: String,
    imageUrls: List<String>,
    loadedBitmaps: Map<String, Bitmap>,
    isFullscreenOpen: Boolean,
    onImageLoaded: (String, Bitmap) -> Unit,
    onOpenFullscreen: (Int) -> Unit,
) {
    val distinctImageUrls = remember(imageUrls) { imageUrls.filter(String::isNotBlank).distinct() }
    if (distinctImageUrls.isEmpty()) return
    val pagerState = rememberPagerState { distinctImageUrls.size }
    val displayedPage = pagerState.currentPage.coerceIn(0, distinctImageUrls.lastIndex)
    var userPausedUntilElapsedMs by rememberSaveable(distinctImageUrls) { mutableLongStateOf(0L) }
    var autoScrollInProgress by remember { mutableStateOf(false) }
    var segmentPreviousPage by remember(distinctImageUrls) { mutableIntStateOf(displayedPage) }
    var segmentCurrentPage by remember(distinctImageUrls) { mutableIntStateOf(displayedPage) }
    var segmentTransitionDirection by remember(distinctImageUrls) {
        mutableStateOf(GallerySegmentTransitionDirection.Forward)
    }
    fun pauseAutoRotationForUserInteraction() {
        userPausedUntilElapsedMs = SystemClock.elapsedRealtime() + GalleryUserInteractionPauseMs
    }
    LaunchedEffect(displayedPage, distinctImageUrls.size) {
        if (displayedPage != segmentCurrentPage) {
            segmentPreviousPage = segmentCurrentPage
            segmentTransitionDirection = resolveGallerySegmentTransitionDirection(
                from = segmentCurrentPage,
                to = displayedPage,
                pageCount = distinctImageUrls.size,
            )
            segmentCurrentPage = displayedPage
        }
    }
    LaunchedEffect(pagerState, distinctImageUrls.size) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && !autoScrollInProgress) {
                    pauseAutoRotationForUserInteraction()
                }
            }
    }
    LaunchedEffect(pagerState, distinctImageUrls.size, userPausedUntilElapsedMs, isFullscreenOpen) {
        if (distinctImageUrls.size <= 1) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val pauseRemaining = userPausedUntilElapsedMs - now
            if (isFullscreenOpen || pauseRemaining > 0L) {
                delay(if (isFullscreenOpen) 120L else pauseRemaining.coerceAtMost(250L))
                continue
            }
            delay(GalleryAutoRotationIntervalMs)
            if (isFullscreenOpen || userPausedUntilElapsedMs > SystemClock.elapsedRealtime()) {
                continue
            }
            val nextPage = (pagerState.currentPage + 1) % distinctImageUrls.size
            autoScrollInProgress = true
            try {
                pagerState.animateScrollToPage(nextPage)
            } finally {
                autoScrollInProgress = false
            }
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.workshop_preview_gallery_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (distinctImageUrls.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.workshop_preview_gallery_counter,
                            displayedPage + 1,
                            distinctImageUrls.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                pageSpacing = 10.dp,
            ) { page ->
                DetailGalleryImage(
                    url = distinctImageUrls[page],
                    cachedBitmap = loadedBitmaps[distinctImageUrls[page]],
                    contentDescription = stringResource(
                        R.string.workshop_preview_gallery_image_content_description,
                        title.ifBlank { stringResource(R.string.workshop_unnamed_mod) },
                        page + 1,
                        distinctImageUrls.size,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onClick = {
                        pauseAutoRotationForUserInteraction()
                        onOpenFullscreen(page)
                    },
                    onLoaded = { bitmap ->
                        onImageLoaded(distinctImageUrls[page], bitmap)
                    },
                )
            }
            if (distinctImageUrls.size > 1) {
                GallerySegmentedProgressBar(
                    pageCount = distinctImageUrls.size,
                    previousPage = segmentPreviousPage,
                    currentPage = segmentCurrentPage,
                    transitionDirection = segmentTransitionDirection,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GallerySegmentedProgressBar(
    pageCount: Int,
    previousPage: Int,
    currentPage: Int,
    transitionDirection: GallerySegmentTransitionDirection,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isCurrentSegment = index == currentPage
            val isPreviousSegment = index == previousPage && previousPage != currentPage
            val segmentProgress by animateFloatAsState(
                targetValue = if (isCurrentSegment) 1f else 0f,
                animationSpec = tween(durationMillis = GallerySegmentProgressAnimationMs),
                label = "workshop-gallery-segment-progress",
            )
            val segmentScaleX = when {
                isCurrentSegment -> if (transitionDirection == GallerySegmentTransitionDirection.Forward) 1f else -1f
                isPreviousSegment -> if (transitionDirection == GallerySegmentTransitionDirection.Forward) -1f else 1f
                else -> 1f
            }
            LinearProgressIndicator(
                progress = { segmentProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .graphicsLayer { scaleX = segmentScaleX },
                color = color,
                trackColor = trackColor,
            )
        }
    }
}

@Composable
private fun WorkshopGalleryFullscreenBrowser(
    title: String,
    imageUrls: List<String>,
    loadedBitmaps: Map<String, Bitmap>,
    initialPage: Int,
    modifier: Modifier = Modifier,
    onImageLoaded: (String, Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val distinctImageUrls = remember(imageUrls) { imageUrls.filter(String::isNotBlank).distinct() }
    if (distinctImageUrls.isEmpty()) return
    BackHandler(onBack = onDismiss)
    val initialSafePage = initialPage.coerceIn(0, distinctImageUrls.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialSafePage) { distinctImageUrls.size }
    val displayedPage = pagerState.currentPage.coerceIn(0, distinctImageUrls.lastIndex)
    var segmentPreviousPage by remember(distinctImageUrls) { mutableIntStateOf(displayedPage) }
    var segmentCurrentPage by remember(distinctImageUrls) { mutableIntStateOf(displayedPage) }
    var segmentTransitionDirection by remember(distinctImageUrls) {
        mutableStateOf(GallerySegmentTransitionDirection.Forward)
    }
    val resolvedTitle = title.ifBlank { stringResource(R.string.workshop_unnamed_mod) }

    LaunchedEffect(initialSafePage, distinctImageUrls.size) {
        if (pagerState.currentPage != initialSafePage) {
            pagerState.scrollToPage(initialSafePage)
        }
    }
    LaunchedEffect(displayedPage, distinctImageUrls.size) {
        if (displayedPage != segmentCurrentPage) {
            segmentPreviousPage = segmentCurrentPage
            segmentTransitionDirection = resolveGallerySegmentTransitionDirection(
                from = segmentCurrentPage,
                to = displayedPage,
                pageCount = distinctImageUrls.size,
            )
            segmentCurrentPage = displayedPage
        }
    }

    Surface(
        modifier = modifier,
        color = Color.Black,
        contentColor = Color.White,
    ) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val imageUrl = distinctImageUrls[page]
                FullscreenGalleryImage(
                    url = imageUrl,
                    cachedBitmap = loadedBitmaps[imageUrl],
                    contentDescription = stringResource(
                        R.string.workshop_preview_gallery_image_content_description,
                        resolvedTitle,
                        page + 1,
                        distinctImageUrls.size,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onLoaded = { bitmap -> onImageLoaded(imageUrl, bitmap) },
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.56f),
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, end = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Close,
                            contentDescription = stringResource(R.string.common_action_close),
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.workshop_preview_gallery_counter,
                            displayedPage + 1,
                            distinctImageUrls.size,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (distinctImageUrls.size > 1) {
                GallerySegmentedProgressBar(
                    pageCount = distinctImageUrls.size,
                    previousPage = segmentPreviousPage,
                    currentPage = segmentCurrentPage,
                    transitionDirection = segmentTransitionDirection,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                        .fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.28f),
                )
            }
        }
    }
}

@Composable
private fun DetailGalleryImage(
    url: String,
    cachedBitmap: Bitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLoaded: (Bitmap) -> Unit = {},
) {
    val imageState by rememberGalleryImageState(
        url = url,
        cachedBitmap = cachedBitmap,
        onLoaded = onLoaded,
    )
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = imageState) {
                GalleryImageState.Loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                GalleryImageState.Failed -> Text(
                    text = stringResource(R.string.workshop_preview_gallery_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is GalleryImageState.Loaded -> Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun FullscreenGalleryImage(
    url: String,
    cachedBitmap: Bitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onLoaded: (Bitmap) -> Unit = {},
) {
    val imageState by rememberGalleryImageState(
        url = url,
        cachedBitmap = cachedBitmap,
        onLoaded = onLoaded,
    )
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = imageState) {
            GalleryImageState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White.copy(alpha = 0.82f),
                strokeWidth = 2.dp,
            )
            GalleryImageState.Failed -> Text(
                text = stringResource(R.string.workshop_preview_gallery_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
            is GalleryImageState.Loaded -> {
                val painter = remember(current.bitmap) { BitmapPainter(current.bitmap.asImageBitmap()) }
                val source = remember(painter) { PainterZoomableImageSource(painter) }
                ZoomableImage(
                    image = source,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun rememberGalleryImageState(
    url: String,
    cachedBitmap: Bitmap?,
    onLoaded: (Bitmap) -> Unit,
): State<GalleryImageState> {
    val context = LocalContext.current
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    return produceState<GalleryImageState>(
        initialValue = cachedBitmap?.let(GalleryImageState::Loaded) ?: GalleryImageState.Loading,
        key1 = url,
        key2 = cachedBitmap,
    ) {
        cachedBitmap?.let { bitmap ->
            value = GalleryImageState.Loaded(bitmap)
            return@produceState
        }
        value = when {
            url.isBlank() -> GalleryImageState.Failed
            else -> withContext(Dispatchers.IO) {
                RemoteBitmapCacheStore.load(context.applicationContext, url)
            }?.let { bitmap ->
                currentOnLoaded(bitmap)
                GalleryImageState.Loaded(bitmap)
            } ?: GalleryImageState.Failed
        }
    }
}

private sealed interface GalleryImageState {
    data object Loading : GalleryImageState
    data object Failed : GalleryImageState
    data class Loaded(val bitmap: Bitmap) : GalleryImageState
}

private class PainterZoomableImageSource(
    private val painter: BitmapPainter,
) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        return remember(painter) {
            ZoomableImageSource.ResolveResult(
                delegate = ZoomableImageSource.PainterDelegate(painter),
            )
        }
    }
}

private enum class GallerySegmentTransitionDirection {
    Forward,
    Backward,
}

private fun resolveGallerySegmentTransitionDirection(
    from: Int,
    to: Int,
    pageCount: Int,
): GallerySegmentTransitionDirection = when {
    pageCount <= 1 || from == to -> GallerySegmentTransitionDirection.Forward
    from == pageCount - 1 && to == 0 -> GallerySegmentTransitionDirection.Forward
    from == 0 && to == pageCount - 1 -> GallerySegmentTransitionDirection.Backward
    to > from -> GallerySegmentTransitionDirection.Forward
    else -> GallerySegmentTransitionDirection.Backward
}

private const val GalleryAutoRotationIntervalMs = 4_000L
private const val GalleryUserInteractionPauseMs = 5_000L
private const val GallerySegmentProgressAnimationMs = 240
private const val GalleryFullscreenEnterAnimationMs = 220
private const val GalleryFullscreenExitAnimationMs = 180
private const val WorkshopDetailLoadCompleteAnimationMs = 260
private const val WorkshopDetailLoadCompleteInitialScale = 0.985f
private const val WorkshopDetailLoadCompleteOvershootScale = 1.012f

@Composable
private fun WorkshopSubscribeConfirmDialog(
    modTitle: String,
    dontRemind: Boolean,
    onDontRemindChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workshop_subscribe_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.workshop_subscribe_confirm_message, modTitle))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = dontRemind,
                        onCheckedChange = onDontRemindChange,
                    )
                    Text(
                        text = stringResource(R.string.workshop_subscribe_do_not_remind),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.workshop_action_subscribe_mod))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        },
    )
}

@Composable
private fun WorkshopUnsubscribeConfirmDialog(
    modTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workshop_unsubscribe_confirm_title)) },
        text = { Text(stringResource(R.string.workshop_unsubscribe_confirm_message, modTitle)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.workshop_action_unsubscribe_mod))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        },
    )
}

@Composable
private fun WorkshopSubscribeMessageDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_acknowledge))
            }
        },
    )
}

@Composable
private fun WorkshopChangeNotesDialog(
    title: String,
    changeNotes: WorkshopChangeNotes?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val markdown = changeNotes?.markdown.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(R.string.workshop_change_notes_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    isLoading && markdown.isBlank() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(R.string.workshop_change_notes_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    markdown.isNotBlank() -> SimpleMarkdownCard(
                        title = stringResource(R.string.workshop_change_notes_title),
                        markdown = markdown,
                    )
                    else -> {
                        errorMessage?.let { message ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.workshop_change_notes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_close))
            }
        },
        dismissButton = {
            if (!isLoading && errorMessage != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.workshop_action_retry))
                }
            }
        },
    )
}

@Composable
private fun DetailModCard(
    details: WorkshopItemDetails,
    downloadState: WorkshopModDownloadState,
    subscriptionLoading: Boolean,
    subscribed: Boolean,
    onDownload: () -> Unit,
    onViewChangeNotes: () -> Unit,
    onSubscriptionClick: () -> Unit,
) {
    Card(
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkshopPreviewImage(
                    publishedFileId = details.summary.publishedFileId,
                    url = details.summary.previewUrl,
                    contentDescription = stringResource(R.string.workshop_preview_content_description, details.summary.title),
                    modifier = Modifier.size(112.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = details.summary.title.ifBlank { stringResource(R.string.workshop_unnamed_mod) },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = details.summary.authorName.ifBlank { stringResource(R.string.workshop_unknown_author) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WorkshopDownloadActionButton(
                        state = downloadState,
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    )
                }
            }
            DetailMetaGrid(details = details)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onViewChangeNotes,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.workshop_action_view_log))
                }
                Button(
                    onClick = onSubscriptionClick,
                    enabled = !subscriptionLoading,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    if (subscriptionLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            stringResource(
                                if (subscribed) {
                                    R.string.workshop_action_unsubscribe_mod
                                } else {
                                    R.string.workshop_action_subscribe_mod
                                },
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetaGrid(details: WorkshopItemDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailMetric(
                label = stringResource(R.string.workshop_detail_size),
                value = formatBytes(details.summary.fileSizeBytes),
                modifier = Modifier.weight(1f),
            )
            DetailMetric(
                label = stringResource(R.string.workshop_detail_updated_at),
                value = formatDate(details.summary.updatedAtMillis),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailMetric(
                label = "Workshop ID",
                value = details.summary.publishedFileId.toString(),
                modifier = Modifier.weight(1f),
            )
            DetailMetric(
                label = stringResource(R.string.workshop_detail_download_count),
                value = formatCount(details.summary.downloadCount),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DependencyCard(
    modifier: Modifier = Modifier,
    dependencies: List<WorkshopDependencyUiState>,
    onDownloadDependency: (WorkshopItemSummary) -> Unit,
    onOpenDependency: (WorkshopItemSummary) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.workshop_dependencies_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (dependencies.isEmpty()) stringResource(R.string.workshop_dependencies_empty) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (dependencies.isEmpty()) "0" else "${dependencies.count { it.installed }}/${dependencies.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (dependencies.isNotEmpty()) {
                dependencies.forEach { dependency ->
                    DependencyItemCard(
                        dependency = dependency,
                        onDownload = { onDownloadDependency(dependency.item) },
                        onOpenDetails = { onOpenDependency(dependency.item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyItemCard(
    dependency: WorkshopDependencyUiState,
    onDownload: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(
        onClick = onOpenDetails,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        colors = workshopDetailCardColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopPreviewImage(
                publishedFileId = dependency.item.publishedFileId,
                url = dependency.item.previewUrl,
                contentDescription = stringResource(R.string.workshop_preview_content_description, dependency.item.title),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = dependency.item.title.ifBlank { stringResource(R.string.workshop_dependency_fallback_title, dependency.item.publishedFileId.toString()) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dependency.item.authorName.ifBlank { dependency.item.description.ifBlank { stringResource(dependency.statusLabelResId) } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.workshop_dependency_id_status_format, dependency.item.publishedFileId.toString(), stringResource(dependency.statusLabelResId)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkshopDownloadActionButton(
                state = if (dependency.defaultInstalled) WorkshopModDownloadState.Downloaded else dependency.downloadState,
                onClick = onDownload,
                iconOnly = true,
            )
        }
    }
}

@Composable
private fun DetailDescriptionCard(
    modifier: Modifier = Modifier,
    publishedFileId: ULong,
    text: String,
    fullDescriptionUnavailable: Boolean,
    isTranslating: Boolean,
    isReloading: Boolean,
    translationErrorMessage: String?,
    onRetryFullDescription: () -> Unit,
) {
    var expanded by rememberSaveable(publishedFileId.toString()) { mutableStateOf(false) }
    val description = text.ifBlank { stringResource(R.string.workshop_description_empty) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.workshop_description_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right),
                        contentDescription = if (expanded) stringResource(R.string.workshop_description_collapse) else stringResource(R.string.workshop_description_expand),
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (isTranslating) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.workshop_translate_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (fullDescriptionUnavailable && !isReloading) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.workshop_description_fallback_notice),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = onRetryFullDescription,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.workshop_action_retry_detail))
                        }
                    }
                }
            }
            translationErrorMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCommentsCard(
    modifier: Modifier = Modifier,
    details: WorkshopItemDetails,
    isLoading: Boolean,
    errorMessage: String?,
    isTranslationMode: Boolean,
    isTranslatingComments: Boolean,
    translationErrorMessage: String?,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val hasNoComments = details.commentCount == 0L
    val effectiveErrorMessage = errorMessage.takeUnless { hasNoComments }
    val showInlineLoading = isLoading && details.comments.isEmpty() && !hasNoComments
    val showOverlayLoading = isLoading && details.comments.isNotEmpty() && !hasNoComments
    val overlayAlpha by animateFloatAsState(
        targetValue = if (showOverlayLoading) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "workshop-detail-comments-page-loading-alpha",
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 220)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.workshop_comments_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = commentsSummary(details, showInlineLoading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                effectiveErrorMessage?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(
                                onClick = onRetry,
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.workshop_action_retry_comments)) }
                        }
                    }
                }
                if (isTranslationMode && isTranslatingComments && details.comments.isNotEmpty()) {
                    CommentsLoadingIndicator(
                        text = stringResource(R.string.workshop_comments_translate_loading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
                translationErrorMessage?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (showInlineLoading) {
                    CommentsLoadingIndicator(
                        text = stringResource(R.string.workshop_comments_loading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
                if (details.commentTotalPages?.let { it > 1 } == true ||
                    details.hasPreviousCommentPage ||
                    details.hasNextCommentPage
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onPreviousPage,
                            enabled = !isLoading && details.hasPreviousCommentPage,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.workshop_action_previous_page)) }
                        OutlinedButton(
                            onClick = onNextPage,
                            enabled = !isLoading && details.hasNextCommentPage,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.workshop_action_next_page)) }
                    }
                }
                details.comments.forEach { comment ->
                    CommentItemCard(comment = comment, isTranslationMode = isTranslationMode)
                }
            }

            if (showOverlayLoading || overlayAlpha > 0f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(overlayAlpha),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                    tonalElevation = 2.dp,
                ) {
                    CommentsLoadingIndicator(
                        text = stringResource(R.string.workshop_comments_loading),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentsLoadingIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = " $text",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommentItemCard(
    comment: WorkshopComment,
    isTranslationMode: Boolean,
) {
    val displayedContent = if (isTranslationMode) {
        comment.translatedContent.ifBlank { comment.content }
    } else {
        comment.content
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = comment.authorName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCommentTime(comment),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = displayedContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailErrorCard(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.workshop_detail_load_failed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.workshop_action_retry_detail)) }
        }
    }
}

@Composable
private fun WorkshopDetailPrimaryLoadingSkeleton() {
    val skeletonStyle = rememberLoadingSkeletonStyle("workshop_detail_primary_skeleton")
    WorkshopDetailPrimarySkeletonCard(skeletonStyle = skeletonStyle)
}

@Composable
private fun WorkshopDetailPrimarySkeletonCard(skeletonStyle: LoadingSkeletonStyle) {
    Card(
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingSkeletonBlock(
                    modifier = Modifier.size(112.dp),
                    style = skeletonStyle,
                    shape = RoundedCornerShape(18.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .height(24.dp),
                        style = skeletonStyle,
                    )
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.48f)
                            .height(14.dp),
                        style = skeletonStyle,
                    )
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        style = skeletonStyle,
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(2) { index ->
                            DetailMetricSkeleton(
                                skeletonStyle = skeletonStyle,
                                modifier = Modifier.weight(1f),
                                valueWidthFraction = if (index == 0) 0.72f else 0.54f,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(2) {
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        style = skeletonStyle,
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMetricSkeleton(
    skeletonStyle: LoadingSkeletonStyle,
    modifier: Modifier = Modifier,
    valueWidthFraction: Float,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 68.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(10.dp),
                style = skeletonStyle,
            )
            LoadingSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(valueWidthFraction)
                    .height(16.dp),
                style = skeletonStyle,
            )
        }
    }
}

@Composable
private fun WorkshopDetailGallerySkeletonCard(skeletonStyle: LoadingSkeletonStyle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .width(72.dp)
                        .height(20.dp),
                    style = skeletonStyle,
                )
                Spacer(modifier = Modifier.weight(1f))
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .width(42.dp)
                        .height(16.dp),
                    style = skeletonStyle,
                )
            }
            LoadingSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                style = skeletonStyle,
                shape = RoundedCornerShape(18.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        style = skeletonStyle,
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkshopDetailDescriptionSkeletonCard(skeletonStyle: LoadingSkeletonStyle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .width(92.dp)
                        .height(20.dp),
                    style = skeletonStyle,
                )
                Spacer(modifier = Modifier.weight(1f))
                LoadingSkeletonBlock(
                    modifier = Modifier.size(36.dp),
                    style = skeletonStyle,
                    shape = RoundedCornerShape(999.dp),
                )
            }
            val lineWidths = listOf(1f, 0.96f, 0.82f, 0.58f)
            lineWidths.forEach { widthFraction ->
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .height(14.dp),
                    style = skeletonStyle,
                )
            }
        }
    }
}

@Composable
private fun WorkshopDetailCommentsSkeletonCard(skeletonStyle: LoadingSkeletonStyle) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = workshopDetailCardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingSkeletonBlock(
                modifier = Modifier
                    .width(84.dp)
                    .height(20.dp),
                style = skeletonStyle,
            )
            LoadingSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.64f)
                    .height(14.dp),
                style = skeletonStyle,
            )
            repeat(2) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingSkeletonBlock(
                                modifier = Modifier
                                    .width(118.dp)
                                    .height(14.dp),
                                style = skeletonStyle,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            LoadingSkeletonBlock(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(10.dp),
                                style = skeletonStyle,
                            )
                        }
                        LoadingSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(13.dp),
                            style = skeletonStyle,
                        )
                        LoadingSkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.78f)
                                .height(13.dp),
                            style = skeletonStyle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun workshopDetailCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
)

private enum class DetailPrimaryContentState {
    Loading,
    Error,
    Content,
}

private fun AnimatedContentTransitionScope<DetailPrimaryContentState>.workshopDetailPrimaryContentTransition() =
    if (initialState == DetailPrimaryContentState.Loading && targetState == DetailPrimaryContentState.Content) {
        scaleIn(
            initialScale = WorkshopDetailLoadCompleteInitialScale,
            animationSpec = keyframes {
                durationMillis = WorkshopDetailLoadCompleteAnimationMs
                WorkshopDetailLoadCompleteInitialScale at 0
                WorkshopDetailLoadCompleteOvershootScale at 170
                1f at WorkshopDetailLoadCompleteAnimationMs
            },
        ) togetherWith ExitTransition.None
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

@Composable
private fun commentsSummary(details: WorkshopItemDetails, isLoading: Boolean): String = when {
    isLoading && details.comments.isEmpty() -> stringResource(R.string.workshop_comments_summary_loading)
    details.commentCount == 0L -> stringResource(R.string.workshop_comments_summary_empty)
    details.commentCount != null && details.commentTotalPages != null ->
        stringResource(R.string.workshop_comments_summary_pages, details.commentPage, details.commentTotalPages, formatCount(details.commentCount))
    details.commentCount != null ->
        stringResource(R.string.workshop_comments_summary_page, details.commentPage, formatCount(details.commentCount))
    details.comments.isNotEmpty() ->
        stringResource(R.string.workshop_comments_summary_loaded, details.commentPage, details.comments.size)
    else -> stringResource(R.string.workshop_comments_summary_none_loaded)
}

@Composable
private fun formatCommentTime(comment: WorkshopComment): String =
    comment.postedEpochSeconds?.let { formatDate(it * 1000L) }
        ?: comment.postedDisplayText.ifBlank { stringResource(R.string.workshop_unknown_time) }

@Composable
private fun formatCount(value: Long): String {
    if (value <= 0L) return stringResource(R.string.workshop_unknown_value)
    return when {
        value >= 100_000_000L -> stringResource(R.string.workshop_count_hundred_million, value / 100_000_000.0)
        value >= 10_000L -> stringResource(R.string.workshop_count_ten_thousand, value / 10_000.0)
        else -> value.toString()
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return stringResource(R.string.workshop_unknown_value)
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        "${String.format(Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

@Composable
private fun formatDate(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return stringResource(R.string.workshop_unknown_value)
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))
}
