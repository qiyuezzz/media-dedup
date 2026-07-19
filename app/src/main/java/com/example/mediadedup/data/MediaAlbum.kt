package com.example.mediadedup.data

data class MediaAlbum(
    val id: String,
    val name: String,
    val relativePath: String,
    val firstFileUri: android.net.Uri,
    val count: Int,
    val type: MediaType
)
