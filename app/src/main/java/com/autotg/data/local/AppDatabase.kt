package com.autotg.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask

@Database(entities = [Bot::class, Chat::class, ScheduledTask::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun autoTGDao(): AutoTGDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autotg_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
