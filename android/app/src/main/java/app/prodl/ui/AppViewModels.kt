package app.prodl.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.prodl.ProDLApplication
import app.prodl.core.BrowseResult
import app.prodl.core.HealthResponse
import app.prodl.core.InfoResponse
import app.prodl.core.InputParser
import app.prodl.core.NewPipeEngine
import app.prodl.core.SearchItem
import app.prodl.server.LocalServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The single [LocalServer] the Application owns, or null before it exists. */
val AndroidViewModel.server: LocalServer?
    get() = (getApplication<Application>() as? ProDLApplication)?.server

internal fun Throwable.userMessage(): String {
    val raw = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    return raw.lineSequence().first().take(220)
}

// ── Discover ────────────────────────────────────────────────────────────────

data class DiscoverState(
    val query: String = "",
    val type: String = "all",
    val kiosks: List<NewPipeEngine.Kiosk> = emptyList(),
    val kiosk: String = "",
    val items: List<SearchItem> = emptyList(),
    val searching: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    /** Set when the input was a link: the UI navigates and then clears it. */
    val openUrl: String? = null,
    val openKind: InputParser.Kind? = null,
)

class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    init {
        val kiosks = server?.newpipe?.kiosks().orEmpty()
        _state.update { it.copy(kiosks = kiosks, kiosk = kiosks.firstOrNull()?.id.orEmpty()) }
        loadFeed()
    }

    /**
     * Trending feed for [kioskId]. YouTube retired the plain Trending shelf, so
     * an empty or failing kiosk falls through to the next one instead of
     * leaving the screen blank.
     */
    fun loadFeed(kioskId: String? = null) {
        val engine = server?.newpipe ?: return
        val order = engine.kiosks().map { it.id }
        if (order.isEmpty()) {
            _state.update { it.copy(error = "No Discover feed available", loading = false) }
            return
        }
        val requested = kioskId ?: _state.value.kiosk.takeIf { it in order } ?: order.first()
        val start = order.indexOf(requested).coerceAtLeast(0)
        _state.update {
            it.copy(kiosk = requested, loading = true, searching = false, error = null, note = null, query = "")
        }
        viewModelScope.launch {
            var lastError: String? = null
            for (id in order.drop(start) + order.take(start)) {
                val result = runCatching { engine.kiosk(id, FEED_LIMIT) }
                val items = result.getOrNull()
                if (!items.isNullOrEmpty()) {
                    _state.update {
                        it.copy(kiosk = id, items = items, loading = false, error = null)
                    }
                    return@launch
                }
                lastError = result.exceptionOrNull()?.userMessage() ?: "This feed is empty"
            }
            _state.update { it.copy(loading = false, error = lastError ?: "Could not load the feed") }
        }
    }

    fun selectKiosk(id: String) {
        if (id == _state.value.kiosk && !_state.value.loading) return
        loadFeed(id)
    }

    fun selectType(type: String) {
        _state.update { it.copy(type = type) }
        val query = _state.value.query
        if (query.isNotBlank()) search(query, type)
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** One box: links open a screen, everything else becomes a search. */
    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val kind = InputParser.classify(text)
        if (kind == InputParser.Kind.SEARCH) {
            search(text, _state.value.type)
        } else {
            _state.update { it.copy(openUrl = InputParser.normalize(text), openKind = kind) }
        }
    }

    fun consumeOpen() = _state.update { it.copy(openUrl = null, openKind = null) }

    fun search(query: String, type: String = _state.value.type) {
        val media = server?.media ?: return
        _state.update {
            it.copy(query = query, type = type, searching = true, loading = false, error = null, note = null)
        }
        viewModelScope.launch {
            runCatching { media.search(query, SEARCH_LIMIT, type) }
                .onSuccess { response ->
                    _state.update {
                        it.copy(items = response.results, searching = false, note = response.note, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(searching = false, error = failure.userMessage()) }
                }
        }
    }

    /** Leave search results and go back to the feed. */
    fun clearSearch() {
        _state.update { it.copy(query = "") }
        loadFeed()
    }

    private companion object {
        const val FEED_LIMIT = 24
        const val SEARCH_LIMIT = 24
    }
}

// ── Channel / playlist browsing ─────────────────────────────────────────────

data class BrowseState(
    val url: String = "",
    val kind: String = "channel",
    val result: BrowseResult? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    fun load(url: String, kind: String) {
        if (_state.value.url == url && _state.value.result != null) return
        _state.value = BrowseState(url = url, kind = kind, loading = true)
        val engine = server?.newpipe ?: return
        viewModelScope.launch {
            runCatching {
                if (kind == "playlist") engine.playlist(url, BROWSE_LIMIT) else engine.channel(url, BROWSE_LIMIT)
            }
                .onSuccess { result -> _state.update { it.copy(result = result, loading = false) } }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.userMessage()) }
                }
        }
    }

    private companion object {
        const val BROWSE_LIMIT = 40
    }
}

// ── Watch ───────────────────────────────────────────────────────────────────

data class WatchState(
    val url: String = "",
    val info: InfoResponse? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class WatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun load(url: String) {
        if (_state.value.url == url && _state.value.info != null) return
        _state.value = WatchState(url = url, loading = true)
        val media = server?.media ?: return
        viewModelScope.launch {
            // SponsorBlock arrives with the info call: one request, not two.
            runCatching { media.info(url, withSponsorBlock = true, categories = null) }
                .onSuccess { info -> _state.update { it.copy(info = info, loading = false) } }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.userMessage()) }
                }
        }
    }
}

// ── Settings ────────────────────────────────────────────────────────────────

data class SettingsState(
    val health: HealthResponse? = null,
    val cookiesConfigured: Boolean = false,
    val cookieCount: Int = 0,
    val cookiesUpdatedAt: String? = null,
    val serverRunning: Boolean = false,
    val port: Int = 0,
    val loading: Boolean = true,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Reads in-memory engine state, so a short ticker is cheap and honest. */
    fun refresh() {
        val local = server
        val health = runCatching { local?.health() }.getOrNull()
        val cookies = runCatching { local?.cookies?.status() }.getOrNull()
        _state.update {
            it.copy(
                health = health,
                cookiesConfigured = cookies?.configured == true,
                cookieCount = cookies?.cookieCount ?: 0,
                cookiesUpdatedAt = cookies?.updatedAt,
                serverRunning = (local?.port ?: 0) != 0,
                port = local?.port ?: 0,
                loading = false,
            )
        }
    }

    fun clearCookies() {
        runCatching { server?.cookies?.clear() }
        refresh()
    }

    fun startServer(context: android.content.Context) {
        app.prodl.service.ServerService.start(context)
    }

    fun stopServer(context: android.content.Context) {
        app.prodl.service.ServerService.stop(context)
        refresh()
    }
}
