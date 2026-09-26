package space.privatecanvas.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

@Composable
fun PairingQrScanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Surface(color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f), shape = RoundedCornerShape(18.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null)
                Text(ui("CAMERA ACCESS IS NEEDED TO SCAN", "扫描二维码需要相机权限"), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(ui("ALLOW CAMERA", "允许相机"))
                }
            }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }
    AndroidView(
        factory = { previewContext ->
            PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val providerFuture = ProcessCameraProvider.getInstance(previewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (!delivered) {
                                val plane = image.planes.first()
                                val width = image.width
                                val height = image.height
                                val sourceBytes = ByteArray(width * height)
                                val buffer = plane.buffer
                                val rowStride = plane.rowStride
                                val pixelStride = plane.pixelStride
                                for (row in 0 until height) {
                                    for (column in 0 until width) {
                                        sourceBytes[row * width + column] = buffer.get(row * rowStride + column * pixelStride)
                                    }
                                }
                                val source = PlanarYUVLuminanceSource(sourceBytes, width, height, 0, 0, width, height, false)
                                val result = runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
                                if (result != null && result.text.startsWith("PC1.")) {
                                    delivered = true
                                    post { onScanned(result.text) }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(previewContext))
            }
        },
        modifier = Modifier.fillMaxWidth().height(250.dp),
    )
    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }
}
