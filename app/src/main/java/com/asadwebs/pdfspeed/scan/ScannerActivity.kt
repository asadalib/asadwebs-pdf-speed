package com.asadwebs.pdfspeed.scan

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import com.asadwebs.pdfspeed.data.Trace
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.ui.GoldInk
import com.asadwebs.pdfspeed.ui.PDFSpeedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The whole scan session in ONE activity: camera → crop (mayuce) → review
 *  → Next page → camera … → Done. The camera composable stays alive
 *  underneath the crop/review overlays, so its CameraX session binds
 *  exactly once for the whole session and never rebinds when the next page
 *  starts — the rebind on the old navigation return path was what kept
 *  killing the camera on page 2. Finished pages are returned to
 *  MainActivity as file paths in the result intent. */
class ScannerActivity : ComponentActivity() {
    companion object { const val EXTRA_PAGES = "pages" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Trace.init(applicationContext) } catch (e: Exception) { }
        Trace.log("ScannerActivity created")
        setContent {
            PDFSpeedTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    ScannerFlow(
                        onFinish = { pages ->
                            val data = Intent().putExtra(EXTRA_PAGES, pages.map { it.absolutePath }.toTypedArray())
                            setResult(Activity.RESULT_OK, data)
                            finish()
                        },
                        onCancel = { setResult(Activity.RESULT_CANCELED); finish() }
                    )
                }
            }
        }
    }
}

private enum class Step { CAMERA, CROP, REVIEW }

@Composable
private fun ScannerFlow(onFinish: (List<File>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.CAMERA) }
    var pages by remember { mutableStateOf<List<File>>(emptyList()) }
    var curRaw by remember { mutableStateOf<File?>(null) }
    var curMode by remember { mutableStateOf("doc") }
    var curCropped by remember { mutableStateOf<File?>(null) }
    var curPreCropped by remember { mutableStateOf(false) }
    var idFront by remember { mutableStateOf<File?>(null) }

    fun close() { if (pages.isEmpty()) onCancel() else onFinish(pages) }

    fun accept(processed: File, thenFinish: Boolean) {
        Trace.log("scanner accept mode=$curMode finish=$thenFinish")
        if (curMode == "id" && idFront == null) {
            // First side of an ID card: hold it and go straight back to the
            // camera for the back side. Even "Done" means "next" here.
            idFront = processed
            Toast.makeText(context, "Front saved — now scan the BACK side", Toast.LENGTH_LONG).show()
            step = Step.CAMERA
            return
        }
        scope.launch {
            val produced: List<File> = withContext(Dispatchers.IO) {
                try {
                    when (curMode) {
                        "book" -> {
                            val bmp = BitmapFactory.decodeFile(processed.absolutePath)
                            val (l, r) = Imaging.splitSpread(bmp)
                            val f1 = File(context.cacheDir, "book_l_${System.currentTimeMillis()}.jpg")
                            val f2 = File(context.cacheDir, "book_r_${System.currentTimeMillis()}.jpg")
                            Imaging.saveJpeg(l, f1); Imaging.saveJpeg(r, f2)
                            listOf(f1, f2)
                        }
                        "id" -> {
                            val fb = BitmapFactory.decodeFile(idFront!!.absolutePath)
                            val bb = BitmapFactory.decodeFile(processed.absolutePath)
                            val page = Imaging.composeIdPage(fb, bb)
                            val f = File(context.cacheDir, "idcard_${System.currentTimeMillis()}.jpg")
                            Imaging.saveJpeg(page, f)
                            listOf(f)
                        }
                        else -> listOf(processed)
                    }
                } catch (t: Throwable) {
                    Trace.log("accept processing FAILED (${t.javaClass.simpleName}): ${t.message}")
                    listOf(processed)
                }
            }
            if (curMode == "id") idFront = null
            pages = pages + produced
            Trace.log("scanner pages so far: ${pages.size}")
            if (thenFinish) onFinish(pages) else step = Step.CAMERA
        }
    }

    BackHandler {
        when (step) {
            Step.CROP, Step.REVIEW -> step = Step.CAMERA
            Step.CAMERA -> close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScannerScreen(
            enabled = step == Step.CAMERA,
            backSide = idFront != null,
            onCaptured = { file, modeId ->
                curRaw = file
                curMode = modeId
                Trace.log("scanner captured (mode=$modeId) -> crop step")
                step = Step.CROP
            },
            onClose = { close() }
        )

        if (step == Step.CAMERA && pages.isNotEmpty()) {
            Button(
                onClick = { onFinish(pages) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 196.dp)
            ) { Text("✓ Done · ${pages.size} page${if (pages.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
        }

        if (step == Step.CROP) {
            curRaw?.let { raw ->
                CropScreen(
                    imageFile = raw,
                    onCropped = { curCropped = it; curPreCropped = true; step = Step.REVIEW },
                    onFallback = { curCropped = it; curPreCropped = false; step = Step.REVIEW },
                    onCancel = { step = Step.CAMERA }
                )
            }
        }

        if (step == Step.REVIEW) {
            curCropped?.let { cropped ->
                ReviewScreen(
                    imageFile = cropped,
                    initialFilter = modeFilter(curMode),
                    preCropped = curPreCropped,
                    onDone = { p -> accept(p, true) },
                    onNext = { p -> accept(p, false) },
                    onRetake = { step = Step.CAMERA }
                )
            }
        }
    }
}
