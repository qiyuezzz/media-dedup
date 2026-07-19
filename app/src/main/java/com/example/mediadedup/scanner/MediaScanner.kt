package com.example.mediadedup.scanner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.mediadedup.data.MediaAlbum
import com.example.mediadedup.data.MediaFile
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.data.cache.MediaDedupDatabase
import com.example.mediadedup.data.cache.MediaFingerprintEntity
import com.example.mediadedup.data.media.AndroidMediaFingerprintReader
import com.example.mediadedup.data.media.MediaFingerprintReader
import com.example.mediadedup.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class MediaScanner(private val context: Context) {

    private val fingerprintReader: MediaFingerprintReader =
        AndroidMediaFingerprintReader(context)
    private val fingerprintDao by lazy {
        MediaDedupDatabase.get(context).mediaFingerprintDao()
    }

    suspend fun fetchAlbums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val albums = mutableListOf<MediaAlbum>()
        albums.addAll(queryAlbums(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        albums.addAll(queryAlbums(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        albums
    }

    private fun queryAlbums(collection: Uri, type: MediaType): List<MediaAlbum> {
        val albumsMap = mutableMapOf<String, MediaAlbum>()
        val projection = arrayOf(
            "bucket_id",
            "bucket_display_name",
            MediaStore.MediaColumns._ID,
            "relative_path"
        )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val bucketIdColumn = cursor.getColumnIndexOrThrow("bucket_id")
            val bucketNameColumn = cursor.getColumnIndexOrThrow("bucket_display_name")
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathColumn = cursor.getColumnIndexOrThrow("relative_path")

            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn)
                val bucketName = cursor.getString(bucketNameColumn) ?: "Unknown"
                val id = cursor.getLong(idColumn)
                val relativePath = cursor.getString(pathColumn) ?: ""
                val contentUri = Uri.withAppendedPath(collection, id.toString())

                if (!albumsMap.containsKey(bucketId)) {
                    albumsMap[bucketId] = MediaAlbum(
                        id = bucketId,
                        name = bucketName,
                        relativePath = relativePath,
                        firstFileUri = contentUri,
                        count = 1,
                        type = type
                    )
                } else {
                    val existing = albumsMap[bucketId]!!
                    albumsMap[bucketId] = existing.copy(count = existing.count + 1)
                }
            }
        }
        return albumsMap.values.toList().sortedBy { it.name }
    }

    suspend fun scanMedia(selectedBucketIds: Set<String>? = null): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaFiles = mutableListOf<MediaFile>()
        mediaFiles.addAll(queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, selectedBucketIds))
        mediaFiles.addAll(queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, selectedBucketIds))
        mediaFiles.addAll(queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO, selectedBucketIds))
        mediaFiles
    }

    private fun queryMediaStore(collection: Uri, type: MediaType, selectedBucketIds: Set<String>? = null): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            "bucket_id",
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection = if (selectedBucketIds != null && selectedBucketIds.isNotEmpty() && type != MediaType.AUDIO) {
            "bucket_id IN (${selectedBucketIds.joinToString(",") { "'$it'" }})"
        } else {
            null
        }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val favoriteColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durationColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val path = cursor.getString(pathColumn) ?: ""
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeTypeColumn) ?: ""
                val dateAdded = cursor.getLong(dateAddedColumn)
                val contentUri = Uri.withAppendedPath(collection, id.toString())

                val favorite = if (favoriteColumn >= 0) cursor.getInt(favoriteColumn) else 0
                val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0
                val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
                val modifiedAt = if (modifiedColumn >= 0) cursor.getLong(modifiedColumn) else 0L

                val isCameraOriginal = path.contains("/DCIM/Camera", ignoreCase = true)
                val isEdited = EDIT_MARKER_REGEX.containsMatchIn(name) || EDIT_MARKER_REGEX.containsMatchIn(path)

                files.add(
                    MediaFile(
                        id = id,
                        uri = contentUri,
                        name = name,
                        path = path,
                        size = size,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        type = type,
                        isFavorite = favorite,
                        width = width,
                        height = height,
                        durationMs = durationMs,
                        modifiedAt = modifiedAt,
                        isCameraOriginal = isCameraOriginal,
                        isEdited = isEdited
                    )
                )
            }
        }
        return files
    }

    suspend fun calculateHashes(files: List<MediaFile>, onProgress: (Int, Int) -> Unit): List<MediaFile> = withContext(Dispatchers.IO) {
        val sizeGroups = files.groupBy { it.size }.filter { it.value.size > 1 }
        val potentialDuplicates = sizeGroups.values.flatten()
        val total = potentialDuplicates.size
        var current = 0

        potentialDuplicates.forEach { file ->
            if (file.hash == null) {
                file.hash = getFileHash(file.uri)
            }
            current++
            onProgress(current, total)
        }
        
        files
    }

    private fun getFileHash(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            val digest = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compute perceptual hashes for the given files, hydrating from / writing to
     * the [MediaFingerprintEntity] cache where possible.
     *
     * - AUDIO files are skipped (pHash only covers IMAGE/VIDEO).
     * - Cache hits require mediaId + size + modifiedAt + phash version to match;
     *   any drift triggers a recompute.
     * - A single file failure never aborts the scan: it stays [MediaFile.perceptualHash] = null.
     * - [onProgress] is invoked once per file (current, total) so the ViewModel
     *   can drive the [com.example.mediadedup.scanner.ScannerUiState.PerceptualHashing] bar.
     */
    suspend fun calculatePerceptualHashes(
        files: List<MediaFile>,
        onProgress: (Int, Int) -> Unit
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val candidates = files.filter { it.type == MediaType.IMAGE || it.type == MediaType.VIDEO }
        if (candidates.isEmpty()) return@withContext files

        // 1. Hydrate from cache in one batched read.
        val cached: Map<Long, MediaFingerprintEntity> = try {
            fingerprintDao.loadByMediaIds(candidates.map { it.id }).associateBy { it.mediaId }
        } catch (_: Exception) {
            emptyMap()
        }

        val version = PerceptualHash.PERCEPTUAL_HASH_VERSION
        val toCompute = mutableListOf<MediaFile>()
        for (file in candidates) {
            val row = cached[file.id]
            val valid = row != null &&
                row.fileSize == file.size &&
                row.modifiedAt == file.modifiedAt &&
                row.perceptualHashVersion == version &&
                row.perceptualHash != null
            if (valid && row != null) {
                file.perceptualHash = row.perceptualHash
            } else {
                toCompute += file
            }
        }

        // 2. Compute fresh pHash for missing/stale entries, batching DB writes.
        //    Bounded concurrency: a single slow/cloud/corrupt file cannot stall
        //    the whole scan (per-file timeout) and several files decode in
        //    parallel (Semaphore) so the bar actually moves on large libraries.
        val total = candidates.size
        val processedCount = AtomicInteger(candidates.size - toCompute.size)
        // Report cached hits first so the bar starts where we actually are.
        onProgress(processedCount.get(), total)
        Log.i(TAG, "pHash: total=$total cached=${processedCount.get()} toCompute=${toCompute.size}")

        val pendingUpsert = mutableListOf<MediaFingerprintEntity>()
        val semaphore = Semaphore(PHASH_CONCURRENCY)
        val startTime = System.currentTimeMillis()
        coroutineScope {
            for (file in toCompute) {
                launch {
                    semaphore.withPermit {
                        val uri = file.uri
                        val phash = try {
                            withTimeout(PHASH_FILE_TIMEOUT_MS) {
                                when (file.type) {
                                    MediaType.IMAGE -> uri?.let { fingerprintReader.calculateImageHash(it) }
                                    MediaType.VIDEO -> uri?.let { fingerprintReader.calculateVideoHash(it, file.durationMs) }
                                    else -> null
                                }
                            }
                        } catch (e: Exception) {
                            Log.i(TAG, "pHash skip id=${file.id} type=${file.type}: ${e.javaClass.simpleName}: ${e.message}")
                            // TimeoutCancellationException, decode failures, etc.:
                            // skip this file (pHash stays null -> excluded from grouping).
                            null
                        }
                        synchronized(pendingUpsert) {
                            file.perceptualHash = phash
                            pendingUpsert += MediaFingerprintEntity(
                                mediaId = file.id,
                                uri = uri?.toString() ?: "",
                                fileSize = file.size,
                                modifiedAt = file.modifiedAt,
                                exactHash = file.hash,
                                perceptualHash = phash,
                                perceptualHashVersion = version,
                                analyzedAt = System.currentTimeMillis()
                            )
                        }
                        val done = processedCount.incrementAndGet()
                        onProgress(done, total)
                        if (done % 50 == 0) {
                            Log.i(TAG, "pHash progress $done/$total elapsed=${System.currentTimeMillis() - startTime}ms")
                        }
                    }
                }
            }
        }

        Log.i(TAG, "pHash done: ${processedCount.get()}/$total in ${System.currentTimeMillis() - startTime}ms, upserting ${pendingUpsert.size} rows")

        // 3. Persist freshly computed rows (best-effort; cache miss is non-fatal).
        if (pendingUpsert.isNotEmpty()) {
            try {
                fingerprintDao.upsertAll(pendingUpsert)
            } catch (_: Exception) {
                // Swallow: a failed cache write just means recompute next scan.
            }
        }

        files
    }

    companion object {
        private const val TAG = "MediaDedup"
        /** Filename/path fragments that suggest the file is an edited export. */
        private val EDIT_MARKER_REGEX =
            Regex("(?i)\\b(?:EDIT|EDITED|CROP|CROPPED|FILTER|RESIZED|COPY|_1)\\b")

        /**
         * Max concurrent thumbnail/decoding jobs during the pHash pass. Bounds IO
         * pool pressure and heap usage; 6 is a balance between throughput and
         * memory (each decode holds a 64x64+ bitmap briefly).
         */
        private const val PHASH_CONCURRENCY = 6

        /**
         * Per-file hard timeout for thumbnail extraction / video frame decode.
         * A single cloud-backed or corrupt file cannot stall the whole scan
         * beyond this; on timeout the file is skipped (pHash stays null, the
         * file just won't participate in similar grouping).
         */
        private const val PHASH_FILE_TIMEOUT_MS = 5_000L
    }
}
