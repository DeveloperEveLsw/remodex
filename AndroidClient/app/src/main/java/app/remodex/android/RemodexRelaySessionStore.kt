package app.remodex.android

import android.content.Context
import app.remodex.android.core.pairing.RemodexPairingPayload

interface RemodexRelaySessionStore {
    fun read(): RemodexPairingPayload?

    fun write(pairing: RemodexPairingPayload)

    fun clear()
}

class SharedPreferencesRemodexRelaySessionStore(
    context: Context,
) : RemodexRelaySessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): RemodexPairingPayload? {
        val relayUrl = preferences.getString(KEY_RELAY_URL, null)?.trim().orEmpty()
        val sessionId = preferences.getString(KEY_SESSION_ID, null)?.trim().orEmpty()
        if (relayUrl.isEmpty() || sessionId.isEmpty()) {
            return null
        }

        return RemodexPairingPayload(
            relayUrl = relayUrl,
            sessionId = sessionId,
        )
    }

    override fun write(pairing: RemodexPairingPayload) {
        preferences.edit()
            .putString(KEY_RELAY_URL, pairing.normalizedRelayUrl)
            .putString(KEY_SESSION_ID, pairing.normalizedSessionId)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_RELAY_URL)
            .remove(KEY_SESSION_ID)
            .apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "remodex.relay.session"
        private const val KEY_RELAY_URL = "codex.relay.url"
        private const val KEY_SESSION_ID = "codex.relay.sessionId"
    }
}

class InMemoryRemodexRelaySessionStore(
    private var pairing: RemodexPairingPayload? = null,
) : RemodexRelaySessionStore {
    override fun read(): RemodexPairingPayload? = pairing

    override fun write(pairing: RemodexPairingPayload) {
        this.pairing = pairing
    }

    override fun clear() {
        pairing = null
    }
}
