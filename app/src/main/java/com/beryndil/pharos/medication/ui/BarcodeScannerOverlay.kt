package com.beryndil.pharos.medication.ui

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.beryndil.pharos.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen barcode scanner overlay using CameraX + ML Kit.
 *
 * Shows a live camera preview. When any barcode is detected its raw value is delivered
 * exactly once to [onBarcodeScanned] on the main thread, then the overlay is expected to
 * be removed by the caller. Camera permission is requested at runtime if not already granted.
 *
 * Accessibility: the close button has a content description; the scanning hint provides
 * context for TalkBack users who may not see the camera feed.
 */
@Composable
fun BarcodeScannerOverlay(
    onBarcodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Track the latest callback without recreating the AndroidView on recomposition.
    val callbackState = rememberUpdatedState(onBarcodeScanned)
    val handled = remember { AtomicBoolean(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted },
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {

            if (hasCameraPermission) {
                val executor = remember { Executors.newSingleThreadExecutor() }

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val barcodeScanner = BarcodeScanning.getClient()
                        val future = ProcessCameraProvider.getInstance(ctx)

                        future.addListener({
                            val provider = future.get()

                            val preview = Preview.Builder().build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage == null) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val img = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                barcodeScanner.process(img)
                                    .addOnSuccessListener { barcodes ->
                                        val raw = barcodes.firstOrNull()?.rawValue
                                        if (!raw.isNullOrBlank() && handled.compareAndSet(false, true)) {
                                            callbackState.value(raw)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            }

                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis,
                                )
                            } catch (e: Exception) {
                                Log.e("BarcodeScanner", "Camera bind failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.barcode_camera_permission_needed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )
            }

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_barcode_scanner_close),
                    tint = Color.White,
                )
            }

            // Scanning hint
            Text(
                text = stringResource(R.string.barcode_scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
