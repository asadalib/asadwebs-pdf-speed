package com.asadwebs.pdfspeed.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.ui.GoldInk
import com.asadwebs.pdfspeed.ui.Ink
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** What is being scanned: drives the on-screen guide, the two-sided ID flow,
 *  the book-spread split, and the filter Review starts with. */
data class ScanMode(val id: String, val label: String, val guideAspect: Float? = null)

val SCAN_MODES = listOf(
    ScanMode("doc", "Document"),
    ScanMode("id", "ID Card", 1.586f),
    ScanMode("book", "Book"),
    ScanMode("receipt", "Receipt"),
    ScanMode("whiteboard", "Whiteboard"),
    ScanMode("passport", "Passport", 1.42f),
    ScanMode("card", "Card", 1.586f)
)

fun modeFilter(modeId: String): Imaging.ScanFilter = when (modeId) {
    "receipt" -> Imaging.ScanFilter.GRAY
    "passport" -> Imaging.ScanFilter.ORIGINAL
    else -> Imaging.ScanFilter.MAGIC
}

private class CapHolder {
    var capture: ImageCapture? = null
    @Volatile var busy: Boolean = false
    @Volatile var frozen: Boolean = false
}

private class Stab {
    var last: Array<PointF>? = null
    var since: Long = 0
    var lastRun: Long = 0
}

/** CameraX scanner with live edge detection and steady-frame auto-capture.
 *  Binding is lifecycle-safe: it (re)binds on resume and unbinds on dispose,
 *  so coming back from Review never shows a dead black preview. Capture
 *  callbacks always run on the main executor (an earlier crash came from
 *  navigating off a background thread). */
@Composable
fun ScannerScreen(onCaptured: (File, String) -> Unit, onClose: () -> Unit, backSide: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var mode by remember { mutableStateOf(SCAN_MODES[0]) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var autoCap by remember { mutableStateOf(true) }
    var liveCorners by remember { mutableStateOf<Array<PointF>?>(null) }
    val capHolder = remember { CapHolder() }
    val stab = remember { Stab() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    fun capture() {
        val cap = capHolder.capture ?: run {
            Toast.makeText(context, "Camera is starting… one moment.", Toast.LENGTH_SHORT).show()
            return
        }
        if (capHolder.busy) return
        capHolder.busy = true
        val out = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(out).build()
        try {
        cap.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                capHolder.frozen = true
                onCaptured(out, mode.id)
            }
            override fun onError(exception: ImageCaptureException) {
                capHolder.busy = false
                Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
        } catch (e: Exception) {
            capHolder.busy = false
            Toast.makeText(context, "Camera is busy — one moment.", Toast.LENGTH_SHORT).show()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            try {
                val f = File(context.cacheDir, "import_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { input.copyTo(it) }
                } ?: throw IllegalStateException("Could not open that image")
                Handler(Looper.getMainLooper()).post { onCaptured(f, mode.id) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Could not import that image.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    DisposableEffect(previewView, hasPermission, lifecycleOwner) {
        val pv = previewView
        if (pv == null || !hasPermission) return@DisposableEffect onDispose { }
        var provider: ProcessCameraProvider? = null
        var bound = false
        var attempts = 0
        fun bind() {
            val prov = provider ?: return
            try {
                val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - stab.lastRun >= 350 && !capHolder.frozen) {
                            stab.lastRun = now
                            val gray = Imaging.yuvToGray(proxy)
                            val found = if (gray != null) Imaging.detectCorners(Imaging.downscaleTo(gray, 360)) else null
                            liveCorners = found
                            if (found == null) {
                                stab.last = null; stab.since = 0
                            } else {
                                val prev = stab.last
                                val stable = prev != null && (0 until 4).all { i ->
                                    kotlin.math.abs(found[i].x - prev[i].x) + kotlin.math.abs(found[i].y - prev[i].y) < 0.02f
                                }
                                if (stable) {
                                    if (stab.since == 0L) stab.since = now
                                    if (autoCap && now - stab.since > 900 && !capHolder.busy) {
                                        Handler(Looper.getMainLooper()).post { capture() }
                                    }
                                } else {
                                    stab.since = 0
                                }
                                stab.last = found
                            }
                        }
                    } catch (e: Exception) { /* next frame */ }
                    finally { proxy.close() }
                }
                prov.unbindAll()
                val cam = prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                camera = cam
                capHolder.capture = capture
                if (torch) cam.cameraControl.enableTorch(true)
                bound = true
            } catch (e: Exception) {
                bound = false
                attempts++
                if (attempts <= 2) {
                    pv.postDelayed({ if (!bound) bind() }, 600)
                } else {
                    Toast.makeText(context, "Camera could not start. Please close and reopen the scanner.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = try { future.get() } catch (e: Exception) { null }
            bind()
        }, ContextCompat.getMainExecutor(context))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                capHolder.busy = false
                capHolder.frozen = false
                stab.last = null; stab.since = 0
                if (!bound) bind()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { provider?.unbindAll() } catch (e: Exception) { }
            bound = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Canvas(Modifier.fillMaxSize()) {
                if (grid) {
                    val third = Color.White.copy(alpha = 0.3f)
                    drawLine(third, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), 1f)
                    drawLine(third, Offset(2 * size.width / 3, 0f), Offset(2 * size.width / 3, size.height), 1f)
                    drawLine(third, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), 1f)
                    drawLine(third, Offset(0f, 2 * size.height / 3), Offset(size.width, 2 * size.height / 3), 1f)
                }
                mode.guideAspect?.let { aspect ->
                    var w = size.width * 0.88f
                    var h = w / aspect
                    if (h > size.height * 0.6f) { h = size.height * 0.6f; w = h * aspect }
                    val left = (size.width - w) / 2
                    val top = (size.height - h) / 2
                    drawRoundRect(Gold, Offset(left, top), Size(w, h), CornerRadius(18f, 18f), style = Stroke(width = 3f))
                }
                liveCorners?.let { c ->
                    val pts = c.map { Offset(it.x * size.width, it.y * size.height) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    drawPath(path, color = Gold.copy(alpha = 0.18f))
                    drawPath(path, color = Gold, style = Stroke(width = 3f))
                    pts.forEach { drawCircle(Color.White, 9f, it); drawCircle(Gold, 6f, it) }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is needed to scan.", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk)) {
                    Text("Allow camera")
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕ Close", color = Color.White, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.weight(1f))
                TogglePill("AUTO", autoCap) { autoCap = !autoCap }
                Spacer(Modifier.width(6.dp))
                TogglePill("⚡ Flash", torch) {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }
                Spacer(Modifier.width(6.dp))
                TogglePill("▦ Grid", grid) { grid = !grid }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SCAN_MODES.forEach { m ->
                    FilterChip(
                        selected = mode.id == m.id,
                        onClick = { mode = m },
                        label = { Text(m.label, fontSize = 12.5.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = GoldInk, containerColor = Color(0xCC262019), labelColor = Color(0xFFE8E2D4)),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = mode.id == m.id, selectedBorderColor = Gold, borderColor = Color(0xFF4A4234), selectedBorderWidth = 1.dp, borderWidth = 1.dp)
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp)
            .background(Color(0x66000000), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                when {
                    capHolder.busy -> "Capturing…"
                    liveCorners != null && autoCap -> "Edges found — hold steady, capturing automatically"
                    liveCorners != null -> "Edges found"
                    mode.id == "id" && backSide -> "Front saved — now scan the BACK side"
                    mode.id == "id" -> "Fill the frame with the card, front side first"
                    mode.id == "book" -> "Photograph the open spread — it splits into 2 pages"
                    else -> "Point at a document and tap capture"
                },
                color = Color.White, fontSize = 12.5.sp
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 34.dp, start = 28.dp, end = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { gallery.launch("image/*") },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("🖼 Gallery") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { capture() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                modifier = Modifier.size(74.dp).clip(CircleShape)
            ) { Text("●", fontSize = 26.sp, color = Ink) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(104.dp))
        }
    }
}

@Composable
private fun TogglePill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) Gold else Color(0xCC262019))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (on) GoldInk else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
