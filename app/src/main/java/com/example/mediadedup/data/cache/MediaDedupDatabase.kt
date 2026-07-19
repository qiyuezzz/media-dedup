package com.example.mediadedup.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database. Currently only holds the perceptual-hash cache
 * ([MediaFingerprintEntity]); extend [entities] as new tables are added.
 *
 * Access via [get] which constructs a singleton on first use. Constructed on a
 * background thread by Room internally; callers should not hold the instance on
 * the main thread.
 */
@Database(
    entities = [MediaFingerprintEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MediaDedupDatabase : RoomDatabase() {

    abstract fun mediaFingerprintDao(): MediaFingerprintDao

    companion object {
        @Volatile
        private var instance: MediaDedupDatabase? = null

        fun get(context: Context): MediaDedupDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDedupDatabase::class.java,
                    "mediadedup.db"
                ).build().also { instance = it }
            }
        }
    }
}
