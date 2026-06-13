package com.justb81.compassduel.di

import android.content.Context
import android.hardware.SensorManager
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.engine.GameEngine
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.NearbyConnectionManager
import com.justb81.compassduel.net.ReliableMessageTransport
import com.justb81.compassduel.session.GameEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
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
 * Qualifier for the single-threaded game-loop dispatcher.
 *
 * All engine state mutations and session lobby mutations must run on this dispatcher
 * to guarantee single-threaded access without explicit locking (#61).
 *
 * Provided as [Dispatchers.Default.limitedParallelism(1)]: no extra thread is
 * allocated — it reuses the Default pool but serialises all dispatched work, giving
 * the same happens-before guarantees as a single-threaded executor at zero extra cost.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GameLoopDispatcher

/**
 * Process-wide Preferences DataStore delegate.
 *
 * Declared exactly once at top level and only ever read off the application context (via the
 * provider below) — two delegates, or reads off different contexts, would throw
 * "There are multiple DataStores active for the same file".
 */
private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences",
)

/**
 * Hilt module that provides application-level singletons.
 *
 * Android-framework objects (ConnectionsClient, SensorManager, Vibrator, GameClock)
 * and the application-scoped coroutine scope are bound here and injected wherever
 * needed via constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Binds [MessageTransport] to the [ReliableMessageTransport] decorator wrapping the
     * concrete [NearbyConnectionManager], so control messages get reliable delivery while
     * the high-frequency state stream stays best-effort.
     */
    @Provides
    @Singleton
    fun provideMessageTransport(
        impl: NearbyConnectionManager,
        @ApplicationScope scope: CoroutineScope,
    ): MessageTransport = ReliableMessageTransport(impl, scope)

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
    @GameLoopDispatcher
    fun provideGameLoopDispatcher(): CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    @Provides
    @Singleton
    fun provideGameEngineFactory(): GameEngineFactory =
        GameEngineFactory { rules, clock, scope -> GameEngine(rules, clock, scope) }

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPreferencesDataStore
}
