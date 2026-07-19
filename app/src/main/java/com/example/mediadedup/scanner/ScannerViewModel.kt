package com.example.mediadedup.scanner

import android.app.RecoverableSecurityException
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediadedup.data.MediaAlbum
import com.example.mediadedup.data.MediaFile
import com.example.mediadedup.data.MediaStats
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.data.SimilarMediaGroup
import com.example.mediadedup.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerViewModel(private val mediaScanner: MediaScanner) : ViewModel() {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _duplicateGroups = MutableStateFlow<Map<String, List<MediaFile>>>(emptyMap())
    val duplicateGroups: StateFlow<Map<String, List<MediaFile>>> = _duplicateGroups.asStateFlow()

    private val _emptyFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val emptyFiles: StateFlow<List<MediaFile>> = _emptyFiles.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFiles: StateFlow<Set<Long>> = _selectedFiles.asStateFlow()

    private val _stats = MutableStateFlow(MediaStats())
    val stats: StateFlow<MediaStats> = _stats.asStateFlow()

    private val _selectedCategory = MutableStateFlow<MediaType?>(null)
    val selectedCategory: StateFlow<MediaType?> = _selectedCategory.asStateFlow()

    private val _albums = MutableStateFlow<List<MediaAlbum>>(emptyList())
    val albums: StateFlow<List<MediaAlbum>> = _albums.asStateFlow()

    private val _selectedAlbums = MutableStateFlow<Set<String>>(emptySet())
    val selectedAlbums: StateFlow<Set<String>> = _selectedAlbums.asStateFlow()

    private val _pendingDeleteRequest = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingDeleteRequest: StateFlow<IntentSenderRequest?> = _pendingDeleteRequest.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<SimilarMediaGroup>>(emptyList())
    val similarGroups: StateFlow<List<SimilarMediaGroup>> = _similarGroups.asStateFlow()

    private var allFiles: List<MediaFile> = emptyList()
    private var fullMediaList: List<MediaFile> = emptyList()

    fun loadStats() {
        viewModelScope.launch {
            fullMediaList = mediaScanner.scanMedia()
            updateStats(fullMediaList, _duplicateGroups.value, _similarGroups.value)
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            _albums.value = mediaScanner.fetchAlbums()
        }
    }

    private fun updateStats(
        files: List<MediaFile>,
        groups: Map<String, List<MediaFile>>,
        similar: List<SimilarMediaGroup>
    ) {
        val imageFiles = files.filter { it.type == MediaType.IMAGE }
        val videoFiles = files.filter { it.type == MediaType.VIDEO }
        val audioFiles = files.filter { it.type == MediaType.AUDIO }

        val savings = groups.values.sumOf { group ->
            group.drop(1).sumOf { it.size }
        }
        val dupCount = groups.values.sumOf { it.size - 1 }

        val similarMediaCount = similar.sumOf { it.items.size }
        val similarReclaimable = similar.sumOf { it.reclaimableBytes }

        _stats.value = MediaStats(
            totalSize = files.sumOf { it.size },
            imageSize = imageFiles.sumOf { it.size },
            videoSize = videoFiles.sumOf { it.size },
            audioSize = audioFiles.sumOf { it.size },
            totalCount = files.size,
            imageCount = imageFiles.size,
            videoCount = videoFiles.size,
            audioCount = audioFiles.size,
            potentialSavings = savings,
            duplicateCount = dupCount,
            similarGroupCount = similar.count { it.items.size > 1 },
            similarMediaCount = similarMediaCount,
            similarReclaimableBytes = similarReclaimable
        )
    }

    /**
     * @param global true for a whole-library scan; false restricts to [selectedAlbums].
     * @param filterType restrict to a single media type, or null for all.
     * @param enableNearDuplicate run the perceptual-hash pass and produce similar groups.
     *   No default - callers must be explicit (spec §13.1).
     */
    fun startScan(
        global: Boolean,
        filterType: MediaType?,
        enableNearDuplicate: Boolean
    ) {
        viewModelScope.launch {
            try {
                _selectedFiles.value = emptySet()
                _uiState.value = ScannerUiState.Scanning

                fullMediaList = mediaScanner.scanMedia()

                val bucketIds = if (global) null else _selectedAlbums.value
                val filesToScan = if (global && filterType == null) {
                    fullMediaList
                } else {
                    mediaScanner.scanMedia(bucketIds).let { list ->
                        if (filterType != null) list.filter { it.type == filterType } else list
                    }
                }

                val (empty, nonEmpty) = filesToScan.partition { it.size == 0L }
                _emptyFiles.value = empty

                allFiles = filesToScan

                // ---- Exact (MD5) pass ----
                val potentialDuplicates = nonEmpty.groupBy { it.size }.filter { it.value.size > 1 }.values.flatten()

                _uiState.value = ScannerUiState.Hashing(
                    current = 0,
                    totalPotential = potentialDuplicates.size,
                    totalFiles = filesToScan.size
                )

                val filesWithHash = mediaScanner.calculateHashes(nonEmpty) { current, total ->
                    _uiState.value = ScannerUiState.Hashing(
                        current = current,
                        totalPotential = total,
                        totalFiles = filesToScan.size
                    )
                }

                val groups = filesWithHash
                    .filter { it.hash != null }
                    .groupBy { it.hash!! }
                    .filter { it.value.size > 1 }

                _duplicateGroups.value = groups

                // ---- Near-duplicate (pHash) pass ----
                val similar = if (enableNearDuplicate) {
                    val phashCandidates = filesWithHash
                    val hasVideoCandidates = phashCandidates.any { it.type == MediaType.VIDEO }
                    _uiState.value = ScannerUiState.PerceptualHashing(
                        processedFiles = 0,
                        candidateFiles = phashCandidates.count {
                            it.type == MediaType.IMAGE || it.type == MediaType.VIDEO
                        },
                        hasVideoCandidates = hasVideoCandidates
                    )
                    mediaScanner.calculatePerceptualHashes(phashCandidates) { current, total ->
                        _uiState.value = ScannerUiState.PerceptualHashing(
                            processedFiles = current,
                            candidateFiles = total,
                            hasVideoCandidates = hasVideoCandidates
                        )
                    }
                    _uiState.value = ScannerUiState.GroupingSimilarContent(
                        processedBuckets = 0,
                        totalBuckets = 0
                    )
                    // Grouper is pure CPU over up to ~10k candidates. Even after
                    // the algorithmic fix below, keep it off the Main thread so
                    // Compose can recompose the progress UI and the screen stays
                    // responsive. viewModelScope.launch defaults to Main.immediate.
                    withContext(Dispatchers.Default) {
                        SimilarMediaGrouper.group(phashCandidates)
                    }
                } else {
                    emptyList()
                }
                _similarGroups.value = similar

                updateStats(fullMediaList, groups, similar)
                _uiState.value = ScannerUiState.Finished(
                    groupCount = groups.size,
                    duplicateCount = groups.values.sumOf { it.size - 1 }
                )
            } catch (e: Exception) {
                _uiState.value = ScannerUiState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun toggleAlbumSelection(albumId: String) {
        val current = _selectedAlbums.value.toMutableSet()
        if (current.contains(albumId)) {
            current.remove(albumId)
        } else {
            current.add(albumId)
        }
        _selectedAlbums.value = current
    }

    fun toggleSelection(fileId: Long) {
        val current = _selectedFiles.value.toMutableSet()
        if (current.contains(fileId)) {
            current.remove(fileId)
        } else {
            current.add(fileId)
        }
        _selectedFiles.value = current
    }

    /**
     * Select all non-keep items across EXACT duplicate groups only. Each exact
     * group keeps its first member; the rest are flagged for removal. Empty
     * (size-0) files are also added. Does NOT touch similar groups - see
     * [selectRecommendedSimilarItems].
     */
    fun selectAllExactDuplicates() {
        val allDuplicates = _duplicateGroups.value.values.flatMap { group ->
            group.drop(1).map { it.id }
        }.toMutableSet()

        _emptyFiles.value.forEach { allDuplicates.add(it.id) }

        _selectedFiles.value = allDuplicates
    }

    /**
     * Pre-select the non-recommended (i.e. recommended-for-deletion) items in
     * every similar group (images AND videos). The keep recommendation for each
     * group is left unselected. Selection is additive - it merges into any
     * existing selection rather than replacing it.
     */
    fun selectRecommendedSimilarItems() {
        val current = _selectedFiles.value.toMutableSet()
        _similarGroups.value
            .filter { it.items.size > 1 }
            .forEach { group ->
                group.items.forEach { item ->
                    if (item.file.id != group.keepRecommendation.mediaId) {
                        current.add(item.file.id)
                    }
                }
            }
        _selectedFiles.value = current
    }

    /** Clear every selected id (used by the "deselect all" UI action). */
    fun clearSelection() {
        _selectedFiles.value = emptySet()
    }

    fun selectCategory(type: MediaType?) {
        _selectedCategory.value = type
    }

    fun deleteSelectedFiles(context: Context) {
        val uris = allFiles
            .filter { _selectedFiles.value.contains(it.id) }
            .mapNotNull { it.uri }
        if (uris.isEmpty()) return

        viewModelScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    _pendingDeleteRequest.value = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    uris.forEach { uri ->
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (e: RecoverableSecurityException) {
                            _pendingDeleteRequest.value = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                        }
                    }
                } else {
                    uris.forEach { uri ->
                        context.contentResolver.delete(uri, null, null)
                    }
                    onDeletionComplete()
                }
            } catch (e: Exception) {
            }
        }
    }

    fun onDeletionComplete() {
        _pendingDeleteRequest.value = null
        val deletedIds = _selectedFiles.value
        _selectedFiles.value = emptySet()

        if (deletedIds.isEmpty()) {
            updateStats(fullMediaList, _duplicateGroups.value, _similarGroups.value)
            return
        }

        // Drop the deleted files from the in-memory snapshots so the results
        // list refreshes immediately. We already have hashes for the survivors,
        // so there's no need to re-hash; just recompute the groups.
        allFiles = allFiles.filter { it.id !in deletedIds }
        fullMediaList = fullMediaList.filter { it.id !in deletedIds }

        val remainingGroups = allFiles
            .filter { it.hash != null }
            .groupBy { it.hash!! }
            .filter { it.value.size > 1 }
        _duplicateGroups.value = remainingGroups
        _emptyFiles.value = allFiles.filter { it.size == 0L }

        // Similar groups: remove deleted items, drop singletons, and if the
        // keep-recommendation pointed at a deleted item, recompute it.
        val remainingSimilar = _similarGroups.value
            .mapNotNull { group ->
                val kept = group.items.filter { it.file.id !in deletedIds }
                if (kept.size <= 1) return@mapNotNull null
                // If the previous representative was deleted, pick a new one and
                // recompute distances against it.
                val repId = if (group.keepRecommendation.mediaId in deletedIds) {
                    kept.maxWithOrNull(
                        compareBy<com.example.mediadedup.data.SimilarMediaItem> { it.file.isFavorite }
                            .thenBy { it.file.width.toLong() * it.file.height }
                            .thenBy { it.file.size }
                            .thenBy { it.file.isCameraOriginal }
                            .thenByDescending { it.file.dateAdded }
                    )?.file?.id ?: kept.first().file.id
                } else {
                    group.representativeId
                }
                val rep = kept.first { it.file.id == repId }.file
                val reItems = kept.map { item ->
                    item.copy(
                        distanceToRepresentative = PerceptualHash.hammingDistance(
                            rep.perceptualHash!!,
                            item.file.perceptualHash!!
                        )
                    )
                }
                val rebuilt = group.copy(
                    representativeId = repId,
                    items = reItems,
                    maxDistance = reItems.maxOf { it.distanceToRepresentative }
                )
                if (rebuilt.keepRecommendation.mediaId in deletedIds) {
                    SimilarMediaGrouper.recomputeRecommendation(rebuilt)
                } else {
                    rebuilt
                }
            }
        _similarGroups.value = remainingSimilar

        updateStats(fullMediaList, remainingGroups, remainingSimilar)
    }

    fun resetScan() {
        _uiState.value = ScannerUiState.Idle
        _emptyFiles.value = emptyList()
        _similarGroups.value = emptyList()
        _selectedFiles.value = emptySet()
    }
}

sealed class ScannerUiState {
    object Idle : ScannerUiState()
    object Scanning : ScannerUiState()
    data class Hashing(val current: Int, val totalPotential: Int, val totalFiles: Int) : ScannerUiState()
    data class PerceptualHashing(
        val processedFiles: Int,
        val candidateFiles: Int,
        val hasVideoCandidates: Boolean
    ) : ScannerUiState()
    data class GroupingSimilarContent(val processedBuckets: Int, val totalBuckets: Int) : ScannerUiState()
    data class Finished(val groupCount: Int, val duplicateCount: Int) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
}
