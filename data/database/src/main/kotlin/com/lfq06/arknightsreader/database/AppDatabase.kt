package com.lfq06.arknightsreader.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lfq06.arknightsreader.model.BookFormat
import com.lfq06.arknightsreader.model.LayoutMode
import com.lfq06.arknightsreader.model.MotionPreference
import com.lfq06.arknightsreader.model.TextAlign

/**
 * Application database, version 1. The migration train starts here: every
 * schema change bumps [VERSION] and adds a Migration to Migrations.kt.
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ContentBlockEntity::class,
        ReadingPositionEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        BookSettingsEntity::class,
        FtsEntry::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun blockDao(): BlockDao
    abstract fun positionDao(): PositionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun bookSearchDao(): BookSearchDao

    companion object {
        const val VERSION = 1

        fun build(context: Context, name: String = "arknights-reader.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
