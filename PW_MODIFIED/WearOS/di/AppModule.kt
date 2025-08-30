package com.yourdomain.whisperworks.wear.di

import android.content.Context
import com.yourdomain.whisperworks.wear.data.repository.WearableRepository
import com.yourdomain.whisperworks.wear.data.repository.WearableRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for providing application-level dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides a singleton instance of the WearableRepository
     */
    @Provides
    @Singleton
    fun provideWearableRepository(
        @ApplicationContext context: Context
    ): WearableRepository {
        // We provide the implementation, but the return type is the interface
        // This allows for easier testing and swapping of implementations
        return WearableRepositoryImpl(context)
    }
}
