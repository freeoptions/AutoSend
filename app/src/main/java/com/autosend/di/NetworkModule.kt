package com.autosend.di

import com.autosend.data.remote.FeishuApi
import com.autosend.data.remote.QqApi
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
