package app.remodex.android

import app.remodex.android.core.transport.RemodexTransportState

internal fun connectionStateLabel(state: RemodexTransportState): String {
    return when (state) {
        RemodexTransportState.Disconnected -> "Disconnected"
        is RemodexTransportState.Connecting -> "Connecting ${state.attempt}"
        is RemodexTransportState.Retrying -> "Retrying ${state.attempt}"
        is RemodexTransportState.Connected -> {
            if (state.isInitialized) "Connected" else "Handshaking"
        }

        is RemodexTransportState.Failed -> {
            if (state.isPermanent) "Failed" else "Retry queued"
        }
    }
}
