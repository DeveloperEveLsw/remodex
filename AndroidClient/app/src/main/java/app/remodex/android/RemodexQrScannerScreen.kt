package app.remodex.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun RemodexQrScannerScreen(
    onDismiss: () -> Unit,
    onOpenManualEntry: () -> Unit,
    onValidatedPayload: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: RemodexQrScannerController = remember { RemodexQrScannerController() },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onPermissionRequestCompleted(granted)
    }

    LaunchedEffect(controller, context) {
        controller.onScannerPresented(context) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, controller, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.refreshPermissionStatus(context)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when (uiState.permissionState) {
            RemodexQrCameraPermissionState.Checking -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            RemodexQrCameraPermissionState.Authorized -> {
                RemodexCameraPreview(
                    controller = controller,
                    onValidatedPayload = onValidatedPayload,
                )
                ScannerOverlay(
                    isScanLocked = uiState.isScanLocked,
                    onDismiss = onDismiss,
                    onOpenManualEntry = onOpenManualEntry,
                )
            }

            RemodexQrCameraPermissionState.Denied -> {
                CameraPermissionDeniedState(
                    onDismiss = onDismiss,
                    onOpenManualEntry = onOpenManualEntry,
                )
            }
        }
    }

    uiState.scanErrorMessage?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = controller::dismissScanError,
            title = { Text("Scan Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = controller::dismissScanError) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun RemodexCameraPreview(
    controller: RemodexQrScannerController,
    onValidatedPayload: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val cameraController = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    DisposableEffect(cameraController, lifecycleOwner, barcodeScanner, analysisExecutor) {
        cameraController.setImageAnalysisAnalyzer(analysisExecutor) { imageProxy ->
            analyzeQrFrame(
                imageProxy = imageProxy,
                barcodeScanner = barcodeScanner,
                onDetected = { rawValue, rawBytes ->
                    controller.onQrDetected(
                        rawValue = rawValue,
                        rawBytes = rawBytes,
                        onValidatedPayload = onValidatedPayload,
                    )
                },
            )
        }
        cameraController.bindToLifecycle(lifecycleOwner)

        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                controller = cameraController
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            previewView.controller = cameraController
        },
    )
}

private fun analyzeQrFrame(
    imageProxy: ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onDetected: (String?, ByteArray?) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    barcodeScanner.process(image)
        .addOnSuccessListener { barcodes ->
            val code = barcodes.firstOrNull()
            if (code != null) {
                onDetected(code.rawValue, code.rawBytes)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun ScannerOverlay(
    isScanLocked: Boolean,
    onDismiss: () -> Unit,
    onOpenManualEntry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(256.dp)
                    .border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(24.dp),
                    ),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Scan QR code from Remodex CLI",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            if (isScanLocked) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "Processing scan...",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onOpenManualEntry,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Text("Enter Code Manually")
        }
    }
}

@Composable
private fun CameraPermissionDeniedState(
    onDismiss: () -> Unit,
    onOpenManualEntry: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.76f),
            modifier = Modifier.size(52.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Camera access needed",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Open Settings and allow camera access to scan the pairing QR code.",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        ) {
            Text("Open Settings")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenManualEntry) {
            Text("Enter Code Manually")
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss) {
            Text("Close", color = Color.White)
        }
    }
}
