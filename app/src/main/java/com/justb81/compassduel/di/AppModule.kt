package com.justb81.compassduel.di

import android.content.Context
import android.hardware.SensorManager
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.NearbyConnectionManager
import com.justb81.compassduel.session.GameEngineFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the application-scoped [CoroutineScope]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module that provides application-level singletons.
 *
 * Android-framework objects (ConnectionsClient, SensorManager, Vibrator, GameClock)
 * and the application-scoped coroutine scope are bound here and injected wherever
 * needed via constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /** Binds the concrete [NearbyConnectionManager] to the [MessageTransport] interface. */
    @Binds
    @Singleton
    abstract fun bindMessageTransport(impl: NearbyConnectionManager): MessageTransport

    companion object {

        @Provides
        @Singleton
        fun provideConnectionsClient(@ApplicationContext context: Context): ConnectionsClient =
            Nearby.getConnectionsClient(context)

        @Provides
        @Singleton
        fun provideSensorManager(@ApplicationContext context: Context): SensorManager =
            context.getSystemService(SensorManager::class.java)

        @Provides
        @Singleton
        fun provideVibrator(@ApplicationContext context: Context): Vibrator =
            context.getSystemService(VibratorManager::class.java).defaultVibrator

        @Provides
        @Singleton
        fun provideGameClock(): GameClock = object : GameClock {
            override fun nowMillis(): Long = System.currentTimeMillis()
        }

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun provideGameEngineFactory(): GameEngineFactory =
            GameEngineFactory { rules, clock, scope -> GameEngine(rules, clock, scope) }
    }
}
