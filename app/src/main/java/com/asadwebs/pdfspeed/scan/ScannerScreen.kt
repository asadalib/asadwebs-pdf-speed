package com.asadwebs.pdfspeed.scan

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** CameraX scanner: live preview + capture.
 *  Binding is lifecycle-safe: it (re)binds when the screen resumes and unbinds
 *  when the screen leaves, so coming back from Review never shows a dead
 *  black preview (the earlier stuck-camera bug). */
@Composable
fun ScannerScreen(onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

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
                prov.unbindAll()
                prov.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
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
            if (event == Lifecycle.Event.ON_RESUME && !bound) bind()
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

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕ Close", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Text("Scan", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(64.dp))
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)) {
            Button(
                onClick = {
                    val cap = imageCapture ?: run {
                        Toast.makeText(context, "Camera is starting… one moment.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val out = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(out).build()
                    cap.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) { onCaptured(out) }
                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                modifier = Modifier.size(74.dp).clip(CircleShape)
            ) { Text("●", fontSize = 26.sp, color = Ink) }
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 122.dp)
            .background(Color(0x66000000), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("Point at a document and tap capture", color = Color.White, fontSize = 12.5.sp)
        }
    }
}
