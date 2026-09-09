package com.autosend.di

import android.content.Context
import com.autosend.data.local.AppDatabase
import com.autosend.data.local.AutoSendDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideAutoSendDao(database: AppDatabase): AutoSendDao {
        return database.autoSendDao()
    }
}
