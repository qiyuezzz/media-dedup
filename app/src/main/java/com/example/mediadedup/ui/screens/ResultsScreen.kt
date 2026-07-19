package com.example.mediadedup.ui.screens

import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.mediadedup.R
import com.example.mediadedup.data.MediaFile
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.data.SimilarMediaGroup
import com.example.mediadedup.data.SimilarMediaItem
import com.example.mediadedup.scanner.ScannerViewModel
import com.example.mediadedup.ui.theme.MediaDedupTheme
import com.example.mediadedup.util.formatDuration
import com.example.mediadedup.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: ScannerViewModel,
    onBack: () -> Unit
) {
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val emptyFiles by viewModel.emptyFiles.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val pendingDeleteRequest by viewModel.pendingDeleteRequest.collectAsState()
    val similarGroups by viewModel.similarGroups.collectAsState()
    val context = LocalContext.current
    var previewingVideo by remember { mutableStateOf<MediaFile?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onDeletionComplete()
        }
    }

    LaunchedEffect(pendingDeleteRequest) {
        pendingDeleteRequest?.let {
            deleteLauncher.launch(it)
        }
    }

    val imageSimilar = similarGroups.filter { it.mediaType == MediaType.IMAGE }
    val videoSimilar = similarGroups.filter { it.mediaType == MediaType.VIDEO }
    val hasSimilar = imageSimilar.isNotEmpty() || videoSimilar.isNotEmpty()
    val onPreviewVideo: (MediaFile) -> Unit = { previewingVideo = it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.results_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (duplicateGroups.isNotEmpty() || emptyFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAllExactDuplicates() }) {
                            Text(stringResource(R.string.select_all))
                        }
                    }
                    // Selects the non-keep (recommended-for-deletion) items across all
                    // similar groups, images AND videos. Additive - merges into any
                    // existing selection.
                    if (hasSimilar) {
                        TextButton(onClick = { viewModel.selectRecommendedSimilarItems() }) {
                            Text(stringResource(R.string.select_recommended_items))
                        }
                    }
                    if (selectedFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.deselect_all))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedFiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.deleteSelectedFiles(context) },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                    text = { Text(stringResource(R.string.delete_selected, selectedFiles.size)) }
                )
            }
        }
    ) { innerPadding ->
        if (duplicateGroups.isEmpty() && emptyFiles.isEmpty() && !hasSimilar) {
            Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_duplicates))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 400.dp),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ResultsHeader(stats.duplicateCount, stats.potentialSavings)
                }

                if (emptyFiles.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyFilesSection(
                            files = emptyFiles,
                            selectedFiles = selectedFiles,
                            onToggleSelection = { viewModel.toggleSelection(it) },
                            onPreviewVideo = onPreviewVideo
                        )
                    }
                }

                items(duplicateGroups.entries.toList()) { entry ->
                    DuplicateGroupCard(
                        hash = entry.key,
                        files = entry.value,
                        selectedFiles = selectedFiles,
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        onPreviewVideo = onPreviewVideo
                    )
                }

                if (imageSimilar.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SimilarSectionHeader(
                            title = stringResource(R.string.similar_images_section),
                            reclaimable = stats.similarReclaimableBytes
                        )
                    }
                    items(imageSimilar) { group ->
                        SimilarGroupCard(
                            group = group,
                            selectedFiles = selectedFiles,
                            onToggleSelection = { viewModel.toggleSelection(it) },
                            onPreviewVideo = onPreviewVideo
                        )
                    }
                }

                if (videoSimilar.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SimilarSectionHeader(
                            title = stringResource(R.string.similar_videos_section),
                            reclaimable = 0L // videos: no bulk reclaimable estimate (experimental)
                        )
                    }
                    items(videoSimilar) { group ->
                        SimilarGroupCard(
                            group = group,
                            selectedFiles = selectedFiles,
                            onToggleSelection = { viewModel.toggleSelection(it) },
                            onPreviewVideo = onPreviewVideo
                        )
                    }
                }
            }
        }
    }

    previewingVideo?.let { file ->
        val uri = file.uri
        if (uri != null) {
            VideoPreviewDialog(
                uri = uri,
                title = file.name,
                onDismiss = { previewingVideo = null }
            )
        }
    }
}

@Composable
fun VideoPreviewDialog(
    uri: android.net.Uri,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                        // RESIZE_MODE_FIT (the default on AspectRatioFrameLayout) preserves
                        // the video's aspect ratio with letterboxing - what we want for preview.
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close_preview),
                        tint = Color.White
                    )
                }
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ResultsHeader(count: Int, savings: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.optimization_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.results_summary_format, count, formatSize(savings)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun EmptyFilesSection(
    files: List<MediaFile>,
    selectedFiles: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onPreviewVideo: (MediaFile) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.empty_files),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.empty_files_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            files.forEach { file ->
                MediaFileRow(
                    file = file,
                    isSelected = selectedFiles.contains(file.id),
                    onToggle = { onToggleSelection(file.id) },
                    onPreviewVideo = onPreviewVideo
                )
                if (file != files.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
fun DuplicateGroupCard(
    hash: String,
    files: List<MediaFile>,
    selectedFiles: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onPreviewVideo: (MediaFile) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.duplicate_set, hash.take(8)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            files.forEach { file ->
                MediaFileRow(
                    file = file,
                    isSelected = selectedFiles.contains(file.id),
                    onToggle = { onToggleSelection(file.id) },
                    onPreviewVideo = onPreviewVideo
                )
                if (file != files.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SimilarSectionHeader(title: String, reclaimable: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )
        if (reclaimable > 0L) {
            Text(
                text = stringResource(R.string.estimated_reclaimable_space, formatSize(reclaimable)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SimilarGroupCard(
    group: SimilarMediaGroup,
    selectedFiles: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onPreviewVideo: (MediaFile) -> Unit
) {
    val isVideo = group.mediaType == MediaType.VIDEO
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.similar_group_title, group.items.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.similar_max_distance, group.maxDistance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isVideo) {
                Text(
                    stringResource(R.string.similar_video_experimental),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            group.items.forEach { item ->
                SimilarItemRow(
                    item = item,
                    isKeep = item.file.id == group.keepRecommendation.mediaId,
                    isSelected = selectedFiles.contains(item.file.id),
                    onToggle = { onToggleSelection(item.file.id) },
                    onPreviewVideo = onPreviewVideo
                )
                if (item != group.items.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SimilarItemRow(
    item: SimilarMediaItem,
    isKeep: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onPreviewVideo: (MediaFile) -> Unit
) {
    val isVideo = item.file.type == MediaType.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaThumbnail(
            uri = item.file.uri,
            type = item.file.type,
            modifier = Modifier.size(64.dp),
            onPlayClick = if (isVideo) ({ onPreviewVideo(item.file) }) else null
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isKeep) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                stringResource(R.string.distance_to_representative, item.distanceToRepresentative),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val resolution = if (item.file.width > 0 && item.file.height > 0) {
                "${item.file.width} × ${item.file.height}"
            } else ""
            val duration = formatDuration(item.file.durationMs)
            Text(
                listOf(resolution, formatSize(item.file.size), duration).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
    }
}

/**
 * Shared thumbnail/cover preview for any [MediaFile]. Images render via Coil
 * [AsyncImage]; videos use [ContentResolver.loadThumbnail] on a 128×128 frame
 * (same source the pHash pass sampled, so what the user sees is what was matched).
 * Falls back to a neutral placeholder surface while loading or on failure.
 *
 * When [onPlayClick] is non-null and [type] is [MediaType.VIDEO], a play icon
 * overlay is rendered and the thumbnail becomes the tap target to open the
 * full-screen video preview.
 */
@Composable
fun MediaThumbnail(
    uri: android.net.Uri?,
    type: MediaType,
    modifier: Modifier = Modifier,
    onPlayClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var videoBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        val resolved = uri ?: return@LaunchedEffect
        if (type == MediaType.VIDEO) {
            videoBitmap = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(resolved, Size(128, 128), null)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    val playClick: (() -> Unit)? = if (type == MediaType.VIDEO) onPlayClick else null
    Box(
        modifier = modifier
            .then(
                if (playClick != null) Modifier.clickable { playClick() } else Modifier
            )
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            when {
                type == MediaType.VIDEO && videoBitmap != null -> Image(
                    bitmap = videoBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                uri != null -> AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (playClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = stringResource(R.string.preview_video),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun MediaFileRow(
    file: MediaFile,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onPreviewVideo: (MediaFile) -> Unit
) {
    val isVideo = file.type == MediaType.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaThumbnail(
            uri = file.uri,
            type = file.type,
            modifier = Modifier.size(64.dp),
            onPlayClick = if (isVideo) ({ onPreviewVideo(file) }) else null
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                file.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            val duration = formatDuration(file.durationMs)
            Text(
                listOf(formatSize(file.size), duration).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun ResultsPreviewTablet() {
    MediaDedupTheme {
        Scaffold { innerPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 400.dp),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ResultsHeader(count = 3, savings = 1024L * 1024 * 50)
                }
                items(List(6) { it }) {
                    DuplicateGroupCard(
                        hash = "abcdef123456",
                        files = listOf(
                            MediaFile(1, android.net.Uri.EMPTY, "photo1.jpg", "/sdcard/DCIM/photo1.jpg", 1024L * 1024 * 2, "image/jpeg", 0, MediaType.IMAGE),
                            MediaFile(2, android.net.Uri.EMPTY, "copy_of_photo1.jpg", "/sdcard/Download/copy_of_photo1.jpg", 1024L * 1024 * 2, "image/jpeg", 0, MediaType.IMAGE)
                        ),
                        selectedFiles = setOf(2),
                        onToggleSelection = {},
                        onPreviewVideo = {}
                    )
                }
            }
        }
    }
}
