package com.asadwebs.pdfspeed.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.asadwebs.pdfspeed.data.Trace
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.ui.GoldInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where a PDF to view comes from: a file the app built (a scanned doc's
 *  export), or a content URI picked by the user / handed over by another
 *  app through "Open with PDF Speed". */
sealed interface PdfSource {
    data class Path(val path: String, val title: String) : PdfSource
    data class UriStr(val uri: String, val title: String) : PdfSource
}

/** In-app PDF viewer on the framework PdfRenderer — no separate viewer app
 *  needed. Swipe between pages, pinch to zoom (the page re-renders sharper
 *  when zoomed), double-tap to toggle zoom, share the file out. Renders are
 *  serialized (PdfRenderer is single-threaded) and only the pages around
 *  the current one are kept in memory. */
@Composable
fun PdfViewerScreen(source: PdfSource, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val renderMutex = remember { Mutex() }
    val cache = remember { mutableStateMapOf<Int, Bitmap>() }
    val hiRes = remember { mutableStateMapOf<Int, Boolean>() }

    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val baseWidthPx = (screenW * 1.6f).toInt().coerceIn(1200, 2200)
    val hiWidthPx = (screenW * 3.0f).toInt().coerceIn(2200, 3400)

    LaunchedEffect(source) {
        withContext(Dispatchers.IO) {
            try {
                val fd = when (source) {
                    is PdfSource.Path -> ParcelFileDescriptor.open(File(source.path), ParcelFileDescriptor.MODE_READ_ONLY)
                    is PdfSource.UriStr -> context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
                } ?: throw IllegalStateException("unreadable")
                val r = PdfRenderer(fd)
                pfd = fd; renderer = r; pageCount = r.pageCount
                Trace.log("viewer opened '${source.title}' pages=${r.pageCount}")
                if (r.pageCount == 0) error = "This PDF has no pages to show."
            } catch (e: SecurityException) {
                error = "This PDF is password-protected, so it can't be viewed yet."
            } catch (e: Exception) {
                Trace.log("viewer open FAILED (${e.javaClass.simpleName}): ${e.message}")
                error = "This PDF could not be opened."
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cache.values.forEach { try { it.recycle() } catch (e: Exception) { } }
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
        }
    }

    suspend fun ensurePage(index: Int, hi: Boolean) {
        val r = renderer ?: return
        if (index !in 0 until pageCount) return
        val existing = cache[index]
        if (existing != null && (hiRes[index] == true || !hi)) return
        val targetW = if (hi) hiWidthPx else baseWidthPx
        val bmp = renderMutex.withLock {
            withContext(Dispatchers.IO) {
                val page = r.openPage(index)
                try {
                    val scale = targetW.toFloat() / page.width
                    val b = Bitmap.createBitmap(targetW, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally { page.close() }
            }
        }
        val old = cache[index]
        cache[index] = bmp
        hiRes[index] = hi
        if (old != null && old !== bmp) {
            // Recycle the replaced bitmap a beat later — it may still be
            // on screen in the frame being drawn right now.
            Handler(Looper.getMainLooper()).postDelayed({ try { old.recycle() } catch (e: Exception) { } }, 800)
        }
    }

    fun share() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                when (source) {
                    is PdfSource.Path -> putExtra(Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(source.path)))
                    is PdfSource.UriStr -> putExtra(Intent.EXTRA_STREAM, Uri.parse(source.uri))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Sharing is not available for this file.", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF141210))) {
        when {
            error != null -> {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📄", fontSize = 40.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(error!!, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onClose, shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk)) { Text("Close") }
                }
            }
            renderer == null -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(14.dp))
                    Text("Opening PDF…", color = Color.White, fontSize = 14.sp)
                }
            }
            pageCount > 0 -> {
                val pagerState = rememberPagerState { pageCount }
                var pagerLocked by remember { mutableStateOf(false) }
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { p ->
                        ensurePage(p - 1, false); ensurePage(p, false); ensurePage(p + 1, false)
                        cache.keys.toList().forEach { k ->
                            if (kotlin.math.abs(k - p) > 2) { cache.remove(k)?.recycle(); hiRes.remove(k) }
                        }
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text("✕ Close", color = Color.White, fontWeight = FontWeight.SemiBold) }
                        Text(source.title, color = Color.White, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${pagerState.currentPage + 1} / $pageCount", color = Color(0xFFB8AF9C), fontSize = 13.sp)
                        TextButton(onClick = { share() }) { Text("Share", color = Gold, fontWeight = FontWeight.SemiBold) }
                    }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !pagerLocked,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { page ->
                        PdfPage(
                            bitmap = cache[page],
                            isCurrent = page == pagerState.currentPage,
                            onZoomChange = { locked -> pagerLocked = locked },
                            onWantHiRes = { scope.launch { ensurePage(page, true) } }
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } },
                            enabled = pagerState.currentPage > 0,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("‹ Prev") }
                        Spacer(Modifier.weight(1f))
                        Text("Page ${pagerState.currentPage + 1} of $pageCount", color = Color(0xFFB8AF9C), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1)) } },
                            enabled = pagerState.currentPage < pageCount - 1,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Next ›") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(bitmap: Bitmap?, isCurrent: Boolean, onZoomChange: (Boolean) -> Unit, onWantHiRes: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val tState = rememberTransformableState { zoom, pan, _ ->
        val newScale = (scale * zoom).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale > 1f && boxSize != IntSize.Zero) {
            val maxX = boxSize.width * (newScale - 1f) / 2f
            val maxY = boxSize.height * (newScale - 1f) / 2f
            Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        } else Offset.Zero
    }
    LaunchedEffect(scale, isCurrent) {
        if (isCurrent) onZoomChange(scale > 1.01f)
        if (scale > 1.8f) onWantHiRes()
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .transformable(tState),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y
                )
            )
        } else {
            CircularProgressIndicator(color = Gold)
        }
    }
}
