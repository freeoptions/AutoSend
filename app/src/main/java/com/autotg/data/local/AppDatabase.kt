package com.autotg.data.local

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.FeishuWebhook
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog

@Database(
    entities = [
        Bot::class,
        Chat::class,
        FeishuWebhook::class,
        ScheduledTask::class,
        TaskLog::class
    ],
    version = 10,
    exportSchema = false
)
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
                )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE scheduled_tasks ADD COLUMN parseMode TEXT NOT NULL DEFAULT 'NONE'"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS feishu_webhooks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        webhookUrl TEXT NOT NULL,
                        secret TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE scheduled_tasks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        deliveryChannel TEXT NOT NULL DEFAULT 'TELEGRAM',
                        botId INTEGER,
                        chatId INTEGER,
                        feishuWebhookId INTEGER,
                        content TEXT NOT NULL,
                        parseMode TEXT NOT NULL DEFAULT 'NONE',
                        scheduledTime INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastError TEXT,
                        isEnabled INTEGER NOT NULL,
                        cronExpression TEXT,
                        FOREIGN KEY(botId) REFERENCES bots(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(chatId) REFERENCES chats(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(feishuWebhookId) REFERENCES feishu_webhooks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO scheduled_tasks_new (
                        id, deliveryChannel, botId, chatId, feishuWebhookId, content,
                        parseMode, scheduledTime, status, retryCount, lastError,
                        isEnabled, cronExpression
                    )
                    SELECT
                        id, 'TELEGRAM', botId, chatId, NULL, content,
                        parseMode, scheduledTime, status, retryCount, lastError,
                        isEnabled, cronExpression
                    FROM scheduled_tasks
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE scheduled_tasks")
                database.execSQL("ALTER TABLE scheduled_tasks_new RENAME TO scheduled_tasks")
                database.execSQL("CREATE INDEX index_scheduled_tasks_botId ON scheduled_tasks(botId)")
                database.execSQL("CREATE INDEX index_scheduled_tasks_chatId ON scheduled_tasks(chatId)")
                database.execSQL(
                    "CREATE INDEX index_scheduled_tasks_feishuWebhookId ON scheduled_tasks(feishuWebhookId)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE bots_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        token TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("INSERT INTO bots_new (id, name, token) SELECT id, name, token FROM bots")
                database.execSQL("DROP TABLE bots")
                database.execSQL("ALTER TABLE bots_new RENAME TO bots")

                database.execSQL(
                    """
                    CREATE TABLE chats_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        chatId TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("INSERT INTO chats_new (id, name, chatId) SELECT id, name, chatId FROM chats")
                database.execSQL("DROP TABLE chats")
                database.execSQL("ALTER TABLE chats_new RENAME TO chats")
                database.execSQL("DROP TABLE IF EXISTS avatar_marks")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN lunarMonth INTEGER")
                database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN lunarDay INTEGER")
                database.execSQL(
                    "ALTER TABLE scheduled_tasks ADD COLUMN lunarLeapMonth INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
