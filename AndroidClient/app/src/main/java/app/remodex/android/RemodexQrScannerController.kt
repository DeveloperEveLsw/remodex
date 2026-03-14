package app.remodex.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RemodexQrCameraPermissionState {
    Checking,
    Authorized,
    Denied,
}

data class RemodexQrScannerUiState(
    val permissionState: RemodexQrCameraPermissionState = RemodexQrCameraPermissionState.Checking,
    val scanErrorMessage: String? = null,
    val isScanLocked: Boolean = false,
)

class RemodexQrScannerController(
    private val payloadDecoder: RemodexQrPayloadDecoder = RemodexQrPayloadDecoder(),
) {
    private val _uiState = MutableStateFlow(RemodexQrScannerUiState())
    val uiState: StateFlow<RemodexQrScannerUiState> = _uiState.asStateFlow()

    private var hasRequestedCameraPermission = false

    fun onScannerPresented(context: Context, requestPermission: () -> Unit) {
        if (hasCameraPermission(context)) {
            _uiState.update {
                it.copy(
                    permissionState = RemodexQrCameraPermissionState.Authorized,
                    scanErrorMessage = null,
                    isScanLocked = false,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                permissionState = RemodexQrCameraPermissionState.Checking,
                scanErrorMessage = null,
                isScanLocked = false,
            )
        }

        if (!hasRequestedCameraPermission) {
            hasRequestedCameraPermission = true
            requestPermission()
        } else {
            _uiState.update {
                it.copy(permissionState = RemodexQrCameraPermissionState.Denied)
            }
        }
    }

    fun refreshPermissionStatus(context: Context) {
        _uiState.update { current ->
            current.copy(
                permissionState = if (hasCameraPermission(context)) {
                    RemodexQrCameraPermissionState.Authorized
                } else if (hasRequestedCameraPermission) {
                    RemodexQrCameraPermissionState.Denied
                } else {
                    RemodexQrCameraPermissionState.Checking
                },
            )
        }
    }

    fun onPermissionRequestCompleted(granted: Boolean) {
        _uiState.update { current ->
            current.copy(
                permissionState = if (granted) {
                    RemodexQrCameraPermissionState.Authorized
                } else {
                    RemodexQrCameraPermissionState.Denied
                },
            )
        }
    }

    fun onQrDetected(
        rawValue: String?,
        rawBytes: ByteArray? = null,
        onValidatedPayload: (String) -> Unit,
    ) {
        if (_uiState.value.isScanLocked) {
            return
        }

        _uiState.update { current ->
            current.copy(
                isScanLocked = true,
                scanErrorMessage = null,
            )
        }

        runCatching {
            payloadDecoder.decodeValidatedPayload(
                rawValue = rawValue,
                rawBytes = rawBytes,
            )
        }.onSuccess { validatedPayload ->
            onValidatedPayload(validatedPayload)
        }.onFailure { throwable ->
            _uiState.update { current ->
                current.copy(
                    scanErrorMessage = throwable.message ?: "Invalid QR code.",
                )
            }
        }
    }

    fun dismissScanError() {
        _uiState.update { current ->
            current.copy(
                scanErrorMessage = null,
                isScanLocked = false,
            )
        }
    }

    private fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
