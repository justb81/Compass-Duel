package com.justb81.compassduel.ui.screens.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.justb81.compassduel.data.preferences.UserPreferencesRepository
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.net.ConnectionEvent
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.MessageTransport
import com.justb81.compassduel.net.TransportError
import com.justb81.compassduel.net.protocol.NetMessage
import com.justb81.compassduel.session.GameEngineFactory
import com.justb81.compassduel.session.GameSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [HomeViewModel] — covers the non-blank name guard (#76), the browse-phase delegation,
 * and player-name prefill/persistence. Drives a real [GameSession] over an in-memory
 * [FakeTransport] so the guard's effect on the transport (advertise / request-connection) is
 * observable, and a real DataStore-backed [UserPreferencesRepository] over a temp file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val transport = FakeTransport()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        // HomeViewModel.init launches in viewModelScope (Dispatchers.Main) to restore the name.
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) {
            File(tempDir, "user.preferences_pb")
        }

    private fun buildViewModel(
        prefs: UserPreferencesRepository = UserPreferencesRepository(buildDataStore()),
    ): HomeViewModel {
        val session = GameSession(
            transport = transport,
            clock = object : GameClock {
                override fun nowMillis(): Long = 0L
            },
            // The engine is only created in startMatch(), never during host/join, so this
            // factory is never invoked in these tests.
            engineFactory = GameEngineFactory { _, _, _ -> error("engine not expected in HomeViewModel tests") },
            scope = testScope.backgroundScope,
            gameLoopDispatcher = testDispatcher,
        )
        return HomeViewModel(session, prefs)
    }

    @Test
    fun `hostGame with blank name returns false and does not advertise`() = testScope.runTest {
        val viewModel = buildViewModel()
        viewModel.onPlayerNameChanged("   ")

        assertFalse(viewModel.hostGame())
        yield()

        assertFalse(transport.advertisingStarted)
    }

    @Test
    fun `hostGame trims the name and starts hosting`() = testScope.runTest {
        val viewModel = buildViewModel()
        viewModel.onPlayerNameChanged("  Alice  ")

        assertTrue(viewModel.hostGame())
        yield()

        assertTrue(transport.advertisingStarted)
        assertEquals("Alice", transport.advertisedName)
    }

    @Test
    fun `joinGame with blank name returns false and does not request a connection`() =
        testScope.runTest {
            val viewModel = buildViewModel()
            viewModel.onPlayerNameChanged("")

            assertFalse(viewModel.joinGame("host-ep"))
            yield()

            assertNull(transport.requestedConnection)
        }

    @Test
    fun `joinGame trims the name and requests a connection to the endpoint`() = testScope.runTest {
        val viewModel = buildViewModel()
        viewModel.onPlayerNameChanged("  Bob ")

        assertTrue(viewModel.joinGame("host-ep"))
        yield()

        assertEquals("host-ep" to "Bob", transport.requestedConnection)
    }

    @Test
    fun `startBrowsing and stopBrowsing delegate to the session`() = testScope.runTest {
        val viewModel = buildViewModel()

        viewModel.startBrowsing()
        yield()
        assertTrue(transport.discoveryStarted)

        viewModel.stopBrowsing()
        yield()
        assertTrue(transport.discoveryStopped)
    }

    @Test
    fun `init prefills the saved player name`() = testScope.runTest {
        val prefs = UserPreferencesRepository(buildDataStore())
        prefs.setPlayerName("Saved")

        val viewModel = buildViewModel(prefs)
        advanceUntilIdle()

        assertEquals("Saved", viewModel.uiState.value.playerName)
    }

    @Test
    fun `init does not clobber a name typed before the saved name loads`() = testScope.runTest {
        val prefs = UserPreferencesRepository(buildDataStore())
        prefs.setPlayerName("Saved")

        val viewModel = buildViewModel(prefs)
        viewModel.onPlayerNameChanged("Typed")
        advanceUntilIdle()

        assertEquals("Typed", viewModel.uiState.value.playerName)
    }

    @Test
    fun `hostGame persists the trimmed player name`() = testScope.runTest {
        val prefs = UserPreferencesRepository(buildDataStore())
        val viewModel = buildViewModel(prefs)
        viewModel.onPlayerNameChanged("  Bob  ")

        assertTrue(viewModel.hostGame())
        advanceUntilIdle()

        assertEquals("Bob", prefs.playerName.first())
    }

    /**
     * In-memory transport recording the calls [HomeViewModel] indirectly triggers via
     * [GameSession].
     */
    private class FakeTransport : MessageTransport {

        private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        override val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints

        private val _incomingMessages = MutableSharedFlow<Pair<String, NetMessage>>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val incomingMessages: SharedFlow<Pair<String, NetMessage>> = _incomingMessages

        private val _connectedEndpointIds = MutableStateFlow<Set<String>>(emptySet())
        override val connectedEndpointIds: StateFlow<Set<String>> = _connectedEndpointIds

        private val _transportErrors = MutableSharedFlow<TransportError>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val transportErrors: SharedFlow<TransportError> = _transportErrors

        var advertisingStarted: Boolean = false
        var advertisedName: String? = null
        var discoveryStarted: Boolean = false
        var discoveryStopped: Boolean = false
        var requestedConnection: Pair<String, String>? = null
        override var acceptNewConnections: Boolean = true

        override fun startAdvertising(localName: String) {
            advertisingStarted = true
            advertisedName = localName
        }

        override fun startDiscovery() { discoveryStarted = true }
        override fun stopDiscovery() { discoveryStopped = true }
        override fun requestConnection(endpointId: String, localName: String) {
            requestedConnection = endpointId to localName
        }

        override fun send(endpointId: String, message: NetMessage) = Unit
        override fun broadcast(message: NetMessage) = Unit
        override fun sendReliable(endpointId: String, message: NetMessage) = Unit
        override fun broadcastReliable(message: NetMessage) = Unit
        override fun stopAll() = Unit

        companion object {
            private const val BUFFER_CAPACITY = 64
        }
    }
}
