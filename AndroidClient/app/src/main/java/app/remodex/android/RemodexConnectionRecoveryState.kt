package app.remodex.android

sealed interface RemodexConnectionRecoveryState {
    data object Idle : RemodexConnectionRecoveryState

    data class Retrying(
        val attempt: Int,
        val message: String,
    ) : RemodexConnectionRecoveryState
}
