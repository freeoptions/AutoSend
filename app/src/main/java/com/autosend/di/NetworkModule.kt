package com.autosend.di

import com.autosend.data.remote.FeishuApi
import com.autosend.data.remote.QqApi
import com.autosend.data.remote.TelegramApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val BASE_URL = "https://open.feishu.cn/"

    @Provides
    @Singleton
    fun provideTelegramApi(): TelegramApi = Retrofit.Builder()
        .baseUrl("https://api.telegram.org/")
        .client(OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // 所有重试由持久化队列负责，避免网络库暗中重复 POST。
            .retryOnConnectionFailure(false)
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TelegramApi::class.java)

    @Provides
    @Singleton
    @Named("feishu")
    fun provideFeishuRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideFeishuApi(@Named("feishu") retrofit: Retrofit): FeishuApi {
        return retrofit.create(FeishuApi::class.java)
    }

    @Provides
    @Singleton
    @Named("qq")
    fun provideQqRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.bot.qq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideQqApi(@Named("qq") qqRetrofit: Retrofit): QqApi {
        return qqRetrofit.create(QqApi::class.java)
    }
}
