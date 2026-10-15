/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.auth.MissingYouTubeDataSyncIdException
import moe.rukamori.archivetune.auth.YouTubeLoginAttempt
import moe.rukamori.archivetune.auth.YouTubeLoginRepository
import moe.rukamori.archivetune.auth.YouTubeSessionOrigin
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import javax.inject.Inject

sealed interface LoginScreenState {
    data object Loading : LoginScreenState
    data class Success(val account: LoginAccountUiModel) : LoginScreenState
    data object Empty : LoginScreenState
    data class Error(val error: LoginError) : LoginScreenState
}

@Immutable
data class LoginAccountUiModel(val name: String, val email: String, val channelHandle: String, val dataSyncId: String)

enum class LoginError { MissingDataSyncId, LoginFailed, Network, Http, UnsupportedNavigation, RedirectLoop, RendererLost }

@HiltViewModel
class LoginViewModel @Inject constructor(private val repository: YouTubeLoginRepository) : ViewModel() {
    private val _screenState = MutableStateFlow<LoginScreenState>(LoginScreenState.Loading)
    val screenState: StateFlow<LoginScreenState> = _screenState.asStateFlow()
    private val _webRevision = MutableStateFlow(0L)
    val webRevision: StateFlow<Long> = _webRevision.asStateFlow()
    private var attempt: YouTubeLoginAttempt? = null
    private var loginJob: Job? = null
    private var beginJob: Job? = null
    private var candidate: PlaybackAuthState? = null
    private var submitted: PlaybackAuthState? = null
    private var released = true
    private var completedOrigin: YouTubeSessionOrigin? = null
    private var successConsumed = false

    init {
        viewModelScope.launch {
            repository.changes.collect {
                val owner = attempt
                if (!released && owner != null && !repository.isCurrent(owner) &&
                    loginJob?.isActive != true && _screenState.value !is LoginScreenState.Success) {
                    _screenState.value = LoginScreenState.Error(LoginError.LoginFailed)
                }
            }
        }
    }

    fun consumeSuccess(): Boolean {
        val origin = completedOrigin ?: return false
        if (released || successConsumed) return false
        if (!repository.isCurrent(origin)) {
            _screenState.value = LoginScreenState.Error(LoginError.LoginFailed)
            return false
        }
        successConsumed = true
        return true
    }

    fun open() {
        if (!released) return
        released = false
        begin(retryCandidate = false)
    }

    fun retry() { if (!released) begin(retryCandidate = true) }

    private fun begin(retryCandidate: Boolean) {
        repository.cancel(attempt)
        attempt = null
        loginJob?.cancel()
        beginJob?.cancel()
        submitted = null
        completedOrigin = null
        successConsumed = false
        _screenState.value = LoginScreenState.Loading
        beginJob = viewModelScope.launch {
            try {
                val next = repository.beginAttempt()
                if (released) { repository.cancel(next); return@launch }
                attempt = next
                _webRevision.value += 1
                if (retryCandidate) candidate?.let { submit(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!released) _screenState.value = LoginScreenState.Error(LoginError.LoginFailed)
            }
        }
    }

    fun ownsView(viewRevision: Long): Boolean = !released && viewRevision == _webRevision.value &&
        attempt?.let(repository::isCurrent) == true

    fun onPageLoading(viewRevision: Long) {
        if (ownsView(viewRevision) && _screenState.value !is LoginScreenState.Error && loginJob?.isActive != true) {
            _screenState.value = LoginScreenState.Loading
        }
    }

    fun onPageReady(viewRevision: Long) {
        if (ownsView(viewRevision) && _screenState.value == LoginScreenState.Loading && loginJob?.isActive != true) {
            _screenState.value = LoginScreenState.Empty
        }
    }

    fun onError(viewRevision: Long, error: LoginError) {
        if (!ownsView(viewRevision)) return
        loginJob?.cancel()
        _screenState.value = LoginScreenState.Error(error)
    }

    fun onContextExtracted(viewRevision: Long, cookie: String, visitor: String?, dataSyncId: String?) {
        if (!ownsView(viewRevision) || _screenState.value is LoginScreenState.Error) return
        val next = PlaybackAuthState(cookie = cookie, visitorData = visitor, dataSyncId = dataSyncId).normalized()
        if (candidate != next && next.dataSyncId == null) {
            loginJob?.cancel()
            submitted = null
        }
        candidate = next
        // Wait for the finite extraction budget before reporting a missing page context.
        if (next.dataSyncId != null) submit(next)
    }

    fun onExtractionExhausted(viewRevision: Long) {
        if (ownsView(viewRevision) && _screenState.value !is LoginScreenState.Success && loginJob?.isActive != true) {
            _screenState.value = LoginScreenState.Error(if (candidate?.dataSyncId == null) LoginError.MissingDataSyncId else LoginError.LoginFailed)
        }
    }

    private fun submit(next: PlaybackAuthState) {
        val owner = attempt ?: return
        if (submitted == next) return
        // A newly observed selected context supersedes the old candidate, not the committed
        // account. The repository's private validation mutex keeps cancelled/new work single-flight.
        loginJob?.cancel()
        submitted = next
        loginJob = viewModelScope.launch {
            _screenState.value = LoginScreenState.Loading
            repository.completeLogin(owner, next.cookie.orEmpty(), next.visitorData, next.dataSyncId)
                .onSuccess { session ->
                    // Commit invalidates attempt ownership itself. Only this still-mounted submission
                    // may deliver its success; a later logout/switch cannot receive a stale callback.
                    if (!released && attempt == owner && repository.isCurrent(session.origin)) {
                        completedOrigin = session.origin
                        _screenState.value = LoginScreenState.Success(LoginAccountUiModel(session.accountName,
                            session.accountEmail, session.accountChannelHandle, session.authState.dataSyncId.orEmpty()))
                    }
                }.onFailure { error ->
                    if (!released && attempt == owner) {
                        _screenState.value = LoginScreenState.Error(if (error is MissingYouTubeDataSyncIdException) LoginError.MissingDataSyncId else LoginError.LoginFailed)
                    }
                }
        }
    }

    fun release() {
        released = true
        repository.cancel(attempt)
        attempt = null
        beginJob?.cancel()
        loginJob?.cancel()
        candidate = null
        submitted = null
        completedOrigin = null
    }

    override fun onCleared() { release() }
}
