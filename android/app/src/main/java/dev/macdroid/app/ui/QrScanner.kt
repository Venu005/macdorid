package dev.macdroid.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.macdroid.app.platform.Permissions
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(enabled: Boolean, onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }
    var cameraError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (cameraError) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Camera unavailable. Close other apps using the camera, or enter the details manually.")
            }
        } else if (hasCamera) {
            CameraPreview(enabled, onResult, { cameraError = true }, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Camera permission is needed to scan the code.")
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(16.dp)) { Text("Back") }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(enabled: Boolean, onResult: (String) -> Unit, onCameraError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnResult by rememberUpdatedState(onResult)
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var disposed by remember { mutableStateOf(false) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            analysisUseCase?.clearAnalyzer()
            runCatching { if (providerFuture.isDone) providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(modifier = modifier, factory = { viewContext ->
        val previewView = PreviewView(viewContext)
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || !currentEnabled) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstNotNullOfOrNull { code -> code.rawValue?.takeIf { it.startsWith("macdroid://") } }
                            ?.let { currentOnResult(it) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            analysisUseCase = analysis
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                if (!disposed) onCameraError()
            }
        }, ContextCompat.getMainExecutor(viewContext))
        previewView
    })
}
