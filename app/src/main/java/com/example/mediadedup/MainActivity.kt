package com.example.mediadedup

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.navigation.Route
import com.example.mediadedup.scanner.MediaScanner
import com.example.mediadedup.scanner.ScannerViewModel
import com.example.mediadedup.settings.SettingsManager
import com.example.mediadedup.ui.screens.*
import com.example.mediadedup.ui.theme.MediaDedupTheme
import com.example.mediadedup.util.formatSize
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        
        runBlocking {
            try {
                val lang = settingsManager.languagePreference.first()
                val appLocale: LocaleListCompat = if (lang.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(lang)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
            } catch (e: Exception) {
                // Ignore errors on first run
            }
        }

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides this
            ) {
                MediaDedupTheme {
                    MainScreen(settingsManager)
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scanner = remember { MediaScanner(context) }
    val viewModel: ScannerViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ScannerViewModel(scanner) as T
        }
    })

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) {
            viewModel.loadStats()
        }
    }

    if (permissionState.allPermissionsGranted) {
        AppNavigation(viewModel, settingsManager)
    } else {
        PermissionRequestScreen(
            onGrantClick = { permissionState.launchMultiplePermissionRequest() }
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavigation(viewModel: ScannerViewModel, settingsManager: SettingsManager) {
    val backStack = rememberNavBackStack(Route.Dashboard as NavKey)
    val navigator = rememberListDetailPaneScaffoldNavigator<NavKey>()
    // Read once per navigation composition; scans launched from this tree inherit
    // the value at launch time. Collected with initial=false so the first scan
    // only fires after the stored preference is loaded.
    val enableNearDuplicate by settingsManager.nearDuplicateEnabled
        .collectAsState(initial = false)

    val provider: (NavKey) -> NavEntry<NavKey> = entryProvider {
        entry<Route.Dashboard> {
            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                value = navigator.scaffoldValue,
                listPane = {
                    DashboardScreen(
                        viewModel = viewModel,
                        onStartScan = { global ->
                            viewModel.startScan(global, filterType = null, enableNearDuplicate = enableNearDuplicate)
                            backStack.add(Route.Scanning())
                        },
                        onNavigateToCategory = { type ->
                            if (navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded) {
                                viewModel.selectCategory(type)
                            } else {
                                backStack.add(Route.CategoryDetail(type))
                            }
                        },
                        onNavigateToSettings = {
                            backStack.add(Route.Settings)
                        }
                    )
                },
                detailPane = {
                    DashboardDetailPane(viewModel, onStartScan = { type ->
                        viewModel.startScan(global = true, filterType = type, enableNearDuplicate = enableNearDuplicate)
                        backStack.add(Route.Scanning(type))
                    })
                }
            )
        }
        entry<Route.CategoryDetail> { key ->
            CategoryDetailScreen(
                type = key.type,
                viewModel = viewModel,
                onStartScan = {
                    viewModel.startScan(global = true, filterType = key.type, enableNearDuplicate = enableNearDuplicate)
                    backStack.add(Route.Scanning(key.type))
                },
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) }
            )
        }
        entry<Route.Scanning> {
            ScanningScreen(
                viewModel = viewModel,
                onFinished = {
                    if (backStack.size > 0) backStack.removeAt(backStack.size - 1)
                    backStack.add(Route.Results)
                },
                onBack = {
                    viewModel.resetScan()
                    if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                }
            )
        }
        entry<Route.Results> {
            ResultsScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.resetScan()
                    if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                }
            )
        }
        entry<Route.Settings> {
            SettingsScreen(
                settingsManager = settingsManager,
                onBack = {
                    if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                }
            )
        }
    }

    NavDisplay(
        backStack = backStack,
        entryProvider = provider,
        modifier = Modifier.fillMaxSize(),
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) }
    )
}

@Composable
fun DashboardDetailPane(viewModel: ScannerViewModel, onStartScan: (MediaType) -> Unit) {
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val stats by viewModel.stats.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (selectedCategory == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(32.dp)
            ) {
                @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
                Text(
                    stringResource(R.string.select_category_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 400.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = stringResource(
                            R.string.category_breakdown_format,
                            when (selectedCategory) {
                                MediaType.IMAGE -> stringResource(R.string.category_images)
                                MediaType.VIDEO -> stringResource(R.string.category_videos)
                                MediaType.AUDIO -> stringResource(R.string.category_audio)
                                else -> ""
                            }
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    DetailRow(stringResource(R.string.file_count), when(selectedCategory) {
                        MediaType.IMAGE -> stats.imageCount
                        MediaType.VIDEO -> stats.videoCount
                        MediaType.AUDIO -> stats.audioCount
                        else -> 0
                    }.toString())
                    DetailRow(stringResource(R.string.storage_used), formatSize(when(selectedCategory) {
                        MediaType.IMAGE -> stats.imageSize
                        MediaType.VIDEO -> stats.videoSize
                        MediaType.AUDIO -> stats.audioSize
                        else -> 0L
                    }))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.category_detail_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = { selectedCategory?.let { onStartScan(it) } },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(stringResource(R.string.find_duplicates))
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PermissionRequestScreen(onGrantClick: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(32.dp)
        ) {
            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            Text(
                stringResource(R.string.storage_access_needed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.permission_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onGrantClick,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
    }
}
