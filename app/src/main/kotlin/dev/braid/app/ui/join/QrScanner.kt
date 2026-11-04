package dev.braid.app.ui.join

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * CameraX preview + ZXing decoding of the Y (luminance) plane.
 *
 * Why ZXing instead of ML Kit / Play Services code scanner: no Google Play Services dependency
 * (consistent with the app's "works on any Android" stance), no model download, and a QR code
 * shown on another phone's screen is the easiest possible case for a classic decoder.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var done = false
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                val text = if (done) null else decode(reader, image)
                image.close()
                if (text != null && !done) {
                    done = true
                    ContextCompat.getMainExecutor(context).execute { onResult(text) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
