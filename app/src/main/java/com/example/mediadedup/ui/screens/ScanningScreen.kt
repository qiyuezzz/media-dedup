package com.example.mediadedup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Scanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.mediadedup.R
import com.example.mediadedup.scanner.ScannerUiState
import com.example.mediadedup.scanner.ScannerViewModel
import com.example.mediadedup.ui.theme.MediaDedupTheme

@Composable
fun ScanningScreen(
    viewModel: ScannerViewModel,
    onFinished: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is ScannerUiState.Finished) {
            onFinished()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            ScannerIconStatic()
            
            Spacer(modifier = Modifier.height(48.dp))

            when (val state = uiState) {
                is ScannerUiState.Scanning -> {
                    Text(
                        stringResource(R.string.scanning_media),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.scanning_querying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator()
                }
                is ScannerUiState.Hashing -> {
                    Text(
                        stringResource(R.string.analyzing_content),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.total_files_discovered, state.totalFiles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.potential_duplicates_found, state.totalPotential),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    
                    LinearProgressIndicator(
                        progress = { if (state.totalPotential > 0) state.current.toFloat() / state.totalPotential else 0f },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "${state.current} / ${state.totalPotential}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.hashing_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                is ScannerUiState.PerceptualHashing -> {
                    Text(
                        stringResource(R.string.perceptual_hashing_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.perceptual_hashing_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.candidateFiles > 0) {
                                state.processedFiles.toFloat() / state.candidateFiles
                            } else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "${state.processedFiles} / ${state.candidateFiles}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (state.hasVideoCandidates) {
                        Text(
                            stringResource(R.string.perceptual_hashing_video_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                is ScannerUiState.Error -> {
                    Text(
                        stringResource(R.string.scan_failed),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                }
                is ScannerUiState.GroupingSimilarContent -> {
                    Text(
                        stringResource(R.string.perceptual_hashing_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator()
                }
                else -> {}
            }
        }
    }
}

@Composable
fun ScannerIconStatic() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.size(120.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Scanner,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun ScanningPreview() {
    MediaDedupTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                ScannerIconStatic()
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    "Analyzing Content",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(32.dp))
                LinearProgressIndicator(
                    progress = { 0.6f },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}
