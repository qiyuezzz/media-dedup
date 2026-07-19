package com.example.mediadedup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mediadedup.R
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.scanner.ScannerViewModel
import com.example.mediadedup.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    type: MediaType,
    viewModel: ScannerViewModel,
    onStartScan: () -> Unit,
    onBack: () -> Unit
) {
    val stats by viewModel.stats.collectAsState()

    val (name, icon, color) = when (type) {
        MediaType.IMAGE -> Triple(stringResource(R.string.category_images), Icons.Rounded.Image, MaterialTheme.colorScheme.secondary)
        MediaType.VIDEO -> Triple(stringResource(R.string.category_videos), Icons.Rounded.Videocam, MaterialTheme.colorScheme.tertiary)
        MediaType.AUDIO -> Triple(stringResource(R.string.category_audio), Icons.Rounded.Audiotrack, MaterialTheme.colorScheme.error)
    }

    val count = when(type) {
        MediaType.IMAGE -> stats.imageCount
        MediaType.VIDEO -> stats.videoCount
        MediaType.AUDIO -> stats.audioCount
    }

    val size = when(type) {
        MediaType.IMAGE -> stats.imageSize
        MediaType.VIDEO -> stats.videoSize
        MediaType.AUDIO -> stats.audioSize
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_breakdown_format, name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(120.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(64.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoColumn(stringResource(R.string.file_count), count.toString())
                InfoColumn(stringResource(R.string.storage_used), formatSize(size))
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                stringResource(R.string.category_detail_info),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onStartScan,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = color)
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.find_duplicates), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
