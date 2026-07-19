package com.example.mediadedup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.mediadedup.R
import com.example.mediadedup.data.MediaAlbum
import com.example.mediadedup.data.MediaStats
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.scanner.ScannerViewModel
import com.example.mediadedup.ui.theme.MediaDedupTheme
import com.example.mediadedup.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ScannerViewModel,
    onStartScan: (Boolean) -> Unit,
    onNavigateToCategory: (MediaType) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val stats by viewModel.stats.collectAsState()
    var showScanOptions by remember { mutableStateOf(false) }
    var showAlbumSelection by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val albums by viewModel.albums.collectAsState()
    val selectedAlbums by viewModel.selectedAlbums.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = { viewModel.loadStats() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 80.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StorageSummaryCard(stats)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.media_categories),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    CategoryItem(
                        icon = Icons.Rounded.Image,
                        name = stringResource(R.string.category_images),
                        count = stats.imageCount,
                        size = stats.imageSize,
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = { onNavigateToCategory(MediaType.IMAGE) }
                    )
                }
                item {
                    CategoryItem(
                        icon = Icons.Rounded.Videocam,
                        name = stringResource(R.string.category_videos),
                        count = stats.videoCount,
                        size = stats.videoSize,
                        color = MaterialTheme.colorScheme.tertiary,
                        onClick = { onNavigateToCategory(MediaType.VIDEO) }
                    )
                }
                item {
                    CategoryItem(
                        icon = Icons.Rounded.Audiotrack,
                        name = stringResource(R.string.category_audio),
                        count = stats.audioCount,
                        size = stats.audioSize,
                        color = MaterialTheme.colorScheme.error,
                        onClick = { onNavigateToCategory(MediaType.AUDIO) }
                    )
                }
            }

            // Global Scan Button fixed at bottom
            Button(
                onClick = { showScanOptions = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.find_duplicates), fontWeight = FontWeight.Bold)
            }
        }

        if (showScanOptions) {
            ModalBottomSheet(
                onDismissRequest = { showScanOptions = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.global_scan)) },
                        supportingContent = { Text("Scan all media files on your device") },
                        leadingContent = { Icon(Icons.Rounded.Public, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showScanOptions = false
                            onStartScan(true)
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.select_albums)) },
                        supportingContent = { Text("Choose specific folders or albums to scan") },
                        leadingContent = { Icon(Icons.Rounded.FolderCopy, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showScanOptions = false
                            viewModel.loadAlbums()
                            showAlbumSelection = true
                        }
                    )
                }
            }
        }

        if (showAlbumSelection) {
            ModalBottomSheet(
                onDismissRequest = { showAlbumSelection = false },
                modifier = Modifier.fillMaxHeight(0.8f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        stringResource(R.string.select_albums),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(albums) { album ->
                            AlbumSelectionRow(
                                album = album,
                                isSelected = selectedAlbums.contains(album.id),
                                onToggle = { viewModel.toggleAlbumSelection(album.id) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            showAlbumSelection = false
                            onStartScan(false)
                        },
                        enabled = selectedAlbums.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                    ) {
                        Text(stringResource(R.string.scan_selected, selectedAlbums.size))
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumSelectionRow(
    album: MediaAlbum,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        headlineContent = { Text(album.name) },
        supportingContent = { Text("${album.count} files • ${album.relativePath}") },
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(56.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = album.firstFileUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        trailingContent = {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        },
        modifier = Modifier.clickable { onToggle() }
    )
}

@Composable
fun StorageSummaryCard(stats: MediaStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.total_media_usage),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        formatSize(stats.totalSize),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (stats.potentialSavings > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "-${formatSize(stats.potentialSavings)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryItem(
    icon: ImageVector,
    name: String,
    count: Int,
    size: Long,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.file_count_format, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                formatSize(size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    MediaDedupTheme {
        StorageSummaryCard(
            MediaStats(
                totalSize = 1024L * 1024 * 1024 * 5,
                imageSize = 1024L * 1024 * 500,
                potentialSavings = 1024L * 1024 * 200,
                imageCount = 150
            )
        )
    }
}
