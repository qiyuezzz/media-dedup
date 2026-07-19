package com.example.mediadedup.navigation

import androidx.navigation3.runtime.NavKey
import com.example.mediadedup.data.MediaType
import kotlinx.serialization.Serializable

@Serializable
sealed class Route : NavKey {
    @Serializable
    data object Dashboard : Route()
    
    @Serializable
    data class CategoryDetail(val type: MediaType) : Route()
    
    @Serializable
    data class Scanning(val type: MediaType? = null) : Route()
    
    @Serializable
    data object Results : Route()

    @Serializable
    data object Settings : Route()
}
