package com.inboxiq.app.mcp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the settings screen is in the link flow. */
enum class LinkPhase { IDLE, WORKING, AWAITING_CODE }

/** One-line outcome shown under the controls. Wording lives in strings.xml (relay_notice_*). */
enum class LinkNotice {
    NONE, ENTER_PHONE, CODE_SENT, WRONG_CODE, CODE_EXPIRED, ENTER_SIX_DIGITS, LOCKED,
    SEND_RATE_LIMITED, INVALID_PHONE, DESTINATION_NOT_ALLOWED, DELIVERY_FAILED, NETWORK,
    CONFLICT_NEEDS_CODE, DISCONNECTED_SERVER_UNREACHABLE, DISCONNECTED_NO_REMOTE_REVOKE, REJECTED,
}

data class RelayLinkUiState(
    val phase: LinkPhase = LinkPhase.IDLE,
    val notice: LinkNotice = LinkNotice.NONE,
    val enabled: Boolean = false,
    val challengeId: String? = null,
    /** Server-supplied wait for [LinkNotice.LOCKED] / [LinkNotice.SEND_RATE_LIMITED]; null = not told. */
    val retryAfterSeconds: Long? = null,
    /** Requesting a code (first send or resend) is not allowed before this instant. */
    val blockedUntilMs: Long = 0L,
    val codeExpiresAtMs: Long = 0L,
)

/** What the controller changes outside itself; the Context-backed one is [ContextLinkStore]. */
interface RelayLinkStore {
    fun isEnabled(): Boolean
    /** The relay token, or null if none was ever generated (nothing to revoke). */
    fun existingToken(): String?
    fun token(): String
    fun onLinked(phone: String)
    /** Turn the relay off locally and discard the token. Must work with no network. */
    fun disableLocally()
    fun markReconnectNeeded()
}

class ContextLinkStore(private val context: Context) : RelayLinkStore {
    override fun isEnabled() = RelayConfig.isEnabled(context)
    override fun existingToken() = RelayConfig.existingRelayToken(context)
    override fun token() = RelayConfig.relayToken(context)
    override fun onLinked(phone: String) {
        RelayConfig.setPhoneNumber(context, phone)
        RelayConfig.clearReconnectNeeded(context)
        RelayConfig.setEnabled(context, true)
        RelayRuntime.refresh()
    }
    override fun disableLocally() {
        RelayConfig.disable(context)
        RelayRuntime.refresh()
    }
    override fun markReconnectNeeded() {
        RelayConfig.markReconnectNeeded(context)
        RelayRuntime.refresh()
    }
}

/**
 * State machine behind CloudConnectSection. Order: start verification (relay places a voice call)
 * -> user types the 6-digit code -> confirm. A relay without /phone/link/start (404/405) gets the
 * legacy token-only link instead. Nothing here retries on its own: every network call is
 * user-initiated, so no error path can loop. The code is held only in the call to [submitCode];
 * it is never stored, logged or put in state.
 */
class RelayLinkController(
    private val scope: CoroutineScope,
    private val client: RelayLinkClient,
    private val store: RelayLinkStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RelayLinkUiState(enabled = store.isEnabled()))
    val state: StateFlow<RelayLinkUiState> = _state.asStateFlow()

    /** Bumped by [disconnect]; a reply that arrives for an older epoch is dropped, never applied. */
    @Volatile private var epoch = 0

    /** Call after consent is on record. Also the "Verify" / "Reconnect" action. */
    fun begin(phone: String) {
        if (phone.isBlank()) { _state.update { it.copy(notice = LinkNotice.ENTER_PHONE) }; return }
        requestCode(phone.trim())
    }

    /** Resend; ignored until the cool-down ends. */
    fun resend(phone: String) {
        val s = _state.value
        if (s.phase == LinkPhase.WORKING || now() < s.blockedUntilMs) return
        requestCode(phone.trim())
    }

    fun cancel() {
        if (_state.value.phase == LinkPhase.WORKING) return
        _state.update { it.copy(phase = LinkPhase.IDLE, notice = LinkNotice.NONE, challengeId = null) }
    }

    private fun requestCode(phone: String) {
        val s = _state.value
        if (s.phase == LinkPhase.WORKING || now() < s.blockedUntilMs) return
        _state.update { it.copy(phase = LinkPhase.WORKING, notice = LinkNotice.NONE, retryAfterSeconds = null) }
        val e = epoch
        scope.launch {
            val r = client.startVerification(phone)
            if (e != epoch) return@launch
            when (r) {
                is StartResult.Sent -> _state.update {
                    it.copy(
                        phase = LinkPhase.AWAITING_CODE, notice = LinkNotice.CODE_SENT, challengeId = r.challengeId,
                        blockedUntilMs = now() + RESEND_COOLDOWN_MS,
                        codeExpiresAtMs = now() + r.expiresInSeconds * 1000,
                    )
                }
                // Old relay: no verification endpoint. Fall back to the token-only link, once.
                StartResult.Unsupported -> legacyLink(phone, e)
                is StartResult.RateLimited -> idle(
                    LinkNotice.SEND_RATE_LIMITED, r.retryAfterSeconds,
                    blockMs = (r.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_S) * 1000,
                )
                StartResult.InvalidPhone -> idle(LinkNotice.INVALID_PHONE)
                StartResult.DestinationNotAllowed -> idle(LinkNotice.DESTINATION_NOT_ALLOWED)
                StartResult.DeliveryFailed -> idle(LinkNotice.DELIVERY_FAILED)
                is StartResult.Failed -> idle(LinkNotice.NETWORK)
            }
        }
    }

    private suspend fun legacyLink(phone: String, e: Int) {
        val r = client.link(phone, store.token())
        if (e != epoch) return
        when (r) {
            LinkResult.Linked -> linked(phone)
            // Number is bound to another install: needs the code flow; the user taps Verify again.
            LinkResult.OtpRequired -> idle(LinkNotice.CONFLICT_NEEDS_CODE)
            LinkResult.Unauthorized -> { store.markReconnectNeeded(); idle(LinkNotice.REJECTED) }
            is LinkResult.RateLimited -> idle(
                LinkNotice.SEND_RATE_LIMITED, r.retryAfterSeconds,
                blockMs = (r.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_S) * 1000,
            )
            else -> idle(LinkNotice.NETWORK)
        }
    }

    /** [code] is digits only; anything else is rejected locally without a network call. */
    fun submitCode(phone: String, code: String) {
        val s = _state.value
        if (s.phase != LinkPhase.AWAITING_CODE) return
        val challenge = s.challengeId ?: return
        if (code.length != 6 || !code.all { it in '0'..'9' }) {
            _state.update { it.copy(notice = LinkNotice.ENTER_SIX_DIGITS) }
            return
        }
        _state.update { it.copy(phase = LinkPhase.WORKING, notice = LinkNotice.NONE) }
        val e = epoch
        scope.launch {
            val r = client.confirm(phone.trim(), store.token(), challenge, code)
            if (e != epoch) return@launch
            when (r) {
                LinkResult.Linked -> linked(phone.trim())
                LinkResult.OtpInvalid -> _state.update { it.copy(phase = LinkPhase.AWAITING_CODE, notice = LinkNotice.WRONG_CODE) }
                LinkResult.OtpExpired -> idle(LinkNotice.CODE_EXPIRED, challengeCleared = true)
                is LinkResult.RateLimited -> idle(
                    LinkNotice.LOCKED, r.retryAfterSeconds,
                    blockMs = (r.retryAfterSeconds ?: DEFAULT_LOCKOUT_S) * 1000, challengeCleared = true,
                )
                LinkResult.OtpRequired, LinkResult.Unauthorized -> idle(LinkNotice.REJECTED, challengeCleared = true)
                is LinkResult.Failed -> _state.update { it.copy(phase = LinkPhase.AWAITING_CODE, notice = LinkNotice.NETWORK) }
            }
        }
    }

    /**
     * Turning off / Disconnect. Local state is cleared first and unconditionally; the server call is
     * best-effort and its outcome is reported honestly.
     */
    fun disconnect() {
        epoch++
        val token = store.existingToken()
        store.disableLocally()
        _state.value = RelayLinkUiState(enabled = false)
        if (token == null) return
        scope.launch {
            val notice = when (client.unlink(token)) {
                UnlinkResult.Done -> LinkNotice.NONE
                UnlinkResult.Unsupported -> LinkNotice.DISCONNECTED_NO_REMOTE_REVOKE
                UnlinkResult.Unreachable -> LinkNotice.DISCONNECTED_SERVER_UNREACHABLE
            }
            _state.update { it.copy(notice = notice) }
        }
    }

    private fun linked(phone: String) {
        store.onLinked(phone)
        _state.value = RelayLinkUiState(enabled = true)
    }

    private fun idle(notice: LinkNotice, retryAfter: Long? = null, blockMs: Long = 0, challengeCleared: Boolean = false) {
        _state.update {
            it.copy(
                phase = LinkPhase.IDLE, notice = notice, retryAfterSeconds = retryAfter,
                blockedUntilMs = if (blockMs > 0) now() + blockMs else it.blockedUntilMs,
                challengeId = if (challengeCleared) null else it.challengeId,
            )
        }
    }

    companion object {
        /** Local minimum between code requests; the server also rate-limits (4 calls/number/hour). */
        const val RESEND_COOLDOWN_MS = 60_000L
        /** Used only when the server's 429 carries no Retry-After. PROTOCOL.md: lockout "up to 1 hour". */
        const val DEFAULT_LOCKOUT_S = 3600L
        const val DEFAULT_RATE_LIMIT_S = 900L
    }
}
