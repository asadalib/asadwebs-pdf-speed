package com.asadwebs.pdfspeed.scan

import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.asadwebs.pdfspeed.data.Trace
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.ui.GoldInk
import com.labters.documentscanner.DocumentScannerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Edge adjustment powered by the mayuce AndroidDocumentScanner library
 *  (MIT, OpenCV-based): it detects the document outline and lets the user
 *  drag the corners, with a magnifier. If the library cannot run on this
 *  device for any reason, we fall back to the original photo and the
 *  built-in Review adjustment — this screen is never a dead end. */
@Composable
fun CropScreen(imageFile: File, onCropped: (File) -> Unit, onFallback: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scannerView by remember { mutableStateOf<DocumentScannerView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(failed) {
        if (failed) {
            Trace.log("crop unavailable — falling back to built-in adjustment")
            Toast.makeText(context, "Using the built-in edge adjustment instead.", Toast.LENGTH_SHORT).show()
            onFallback(imageFile)
        }
    }

    LaunchedEffect(scannerView, imageFile) {
        val view = scannerView ?: return@LaunchedEffect
        try {
            val bmp = withContext(Dispatchers.IO) {
                Imaging.downscaleTo(Imaging.loadOriented(imageFile), 2200)
            }
            view.setImage(bmp)
            Trace.log("crop image set (${bmp.width}x${bmp.height})")
        } catch (t: Throwable) {
            Trace.log("crop setImage FAILED (${t.javaClass.simpleName}): ${t.message}")
            failed = true
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF141210))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Retake", color = Color.White) }
            Text("Adjust edges", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading || busy) CircularProgressIndicator(color = Gold, modifier = Modifier.size(20.dp))
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    try {
                        DocumentScannerView(ctx).also { v ->
                            v.setOnLoadListener { l -> loading = l }
                            scannerView = v
                        }
                    } catch (t: Throwable) {
                        Trace.log("crop view create FAILED (${t.javaClass.simpleName}): ${t.message}")
                        failed = true
                        View(ctx)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Text("The outline was detected for you — drag the corners onto the document's edges if needed.",
            color = Color(0xFFCFC8BA), fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !busy,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) { Text("Retake", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    val v = scannerView ?: run { onFallback(imageFile); return@Button }
                    busy = true
                    scope.launch {
                        val out = try {
                            withContext(Dispatchers.Default) {
                                val bmp = v.getCroppedImage()
                                val f = File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                                Imaging.saveJpeg(bmp, f)
                                f
                            }
                        } catch (t: Throwable) {
                            Trace.log("getCroppedImage FAILED (${t.javaClass.simpleName}): ${t.message}")
                            null
                        }
                        busy = false
                        if (out != null) {
                            Trace.log("crop done")
                            onCropped(out)
                        } else {
                            Toast.makeText(context, "Crop failed — using the built-in adjustment.", Toast.LENGTH_SHORT).show()
                            onFallback(imageFile)
                        }
                    }
                },
                enabled = !busy && !loading,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) { Text("Use this crop", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
