package com.asadwebs.pdfspeed.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asadwebs.pdfspeed.data.Trace
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.data.Trace
import com.asadwebs.pdfspeed.ui.GoldInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** After-capture review: Adjust mode (photo + draggable detected corners with
 *  a magnifier loupe) and Preview mode (warped + filtered result). Rotate
 *  buttons re-run detection on the rotated photo. Done / Next page process
 *  the page at up to 3000px and hand the finished JPEG back. */
@Composable
fun ReviewScreen(imageFile: File, onDone: (File) -> Unit, onNext: (File) -> Unit, onRetake: () -> Unit, initialFilter: Imaging.ScanFilter = Imaging.ScanFilter.MAGIC, preCropped: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var master by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf<Array<PointF>?>(null) }
    var detected by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(initialFilter) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var mode by remember { mutableStateOf(if (preCropped) "preview" else "adjust") }
    var dragIdx by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun defaultCorners(): Array<PointF> = arrayOf(
        PointF(0.03f, 0.03f), PointF(0.97f, 0.03f),
        PointF(0.97f, 0.97f), PointF(0.03f, 0.97f)
    )

    LaunchedEffect(imageFile) {
        withContext(Dispatchers.IO) {
            // Memory diet: ONE working bitmap at 3000px serves display,
            // detection, rotation and the final warp. Keeping the full-res
            // camera bitmap alive alongside it was the likely cause of the
            // app closing when a page was processed (Next page / Done).
            val raw = Imaging.loadOriented(imageFile)
            val m = Imaging.downscaleTo(raw, 3000)
            if (m !== raw) raw.recycle()
            original = m
            master = m
            if (preCropped) {
                corners = arrayOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
                detected = true
                Trace.log("review loaded ${m.width}x${m.height}, preCropped (library engine)")
            } else {
                val found = Imaging.detectCorners(m)
                corners = found ?: defaultCorners()
                detected = found != null
                Trace.log("review loaded ${m.width}x${m.height}, detection=${found != null}")
            }
        }
    }

    LaunchedEffect(mode, filter) {
        if (mode != "preview") return@LaunchedEffect
        val bmp = original ?: return@LaunchedEffect
        val c = corners ?: return@LaunchedEffect
        preview = null
        withContext(Dispatchers.IO) {
            val small = Imaging.downscaleTo(master ?: bmp, 1400)
            preview = Imaging.applyFilter(Imaging.warp(small, c), filter)
        }
    }

    fun rotateBy(degCW: Int) {
        val bmp = original ?: return
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                val r = Imaging.rotate(bmp, degCW)
                original = r
                master = r
                val found = Imaging.detectCorners(r)
                corners = found ?: defaultCorners()
                detected = found != null
            }
            preview = null
            mode = "adjust"
            busy = false
        }
    }

    fun processPage(deliver: (File) -> Unit) {
        val bmp = master ?: original ?: return
        val c = corners ?: return
        Trace.log("processPage start filter=$filter")
        busy = true
        scope.launch {
            val outFile = try {
                withContext(Dispatchers.IO) {
                    val done = Imaging.applyFilter(Imaging.warp(bmp, c), filter)
                    val f = File(context.cacheDir, "page_${System.currentTimeMillis()}.jpg")
                    Imaging.saveJpeg(done, f)
                    f
                }
            } catch (t: Throwable) { Trace.log("processPage FAILED (${t.javaClass.simpleName}): ${t.message}"); null }
            busy = false
            if (outFile != null) { Trace.log("processPage done"); deliver(outFile) }
            else Toast.makeText(context, "That page could not be processed. Please retake it a little closer.", Toast.LENGTH_LONG).show()
        }
    }

    // NOTE: bitmaps are deliberately NOT recycled here. In-flight IO work
    // (preview warp, rotate) can still be reading them as this screen
    // leaves; a recycle then crashes natively. They become garbage and
    // the GC reclaims them — the 3000px diet keeps that garbage small.

    Column(Modifier.fillMaxSize().background(Color(0xFF141210))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetake, enabled = !busy) { Text("Retake", color = Color.White) }
            Text("Review", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(color = Gold, modifier = Modifier.size(20.dp))
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            val bmp = original
            val c = corners
            if (bmp == null || c == null) {
                CircularProgressIndicator(color = Gold)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val boxW = maxWidth.value; val boxH = maxHeight.value
                    if (mode == "preview") {
                        val pv = preview
                        if (pv == null) {
                            CircularProgressIndicator(color = Gold, modifier = Modifier.align(Alignment.Center))
                        } else {
                            val s2 = minOf(boxW / pv.width, boxH / pv.height)
                            Image(pv.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.offset(x = ((boxW - pv.width * s2) / 2).dp, y = ((boxH - pv.height * s2) / 2).dp)
                                    .size((pv.width * s2).dp, (pv.height * s2).dp),
                                contentScale = ContentScale.FillBounds)
                        }
                    } else {
                        val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                        val dispW = bmp.width * scale; val dispH = bmp.height * scale
                        val offX = (boxW - dispW) / 2; val offY = (boxH - dispH) / 2
                        Image(bmp.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.offset(x = offX.dp, y = offY.dp).size(dispW.dp, dispH.dp),
                            contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.offset(x = offX.dp, y = offY.dp).size(dispW.dp, dispH.dp)) {
                            val pts = c.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                                close()
                            }
                            drawPath(path, color = Gold.copy(alpha = 0.25f))
                            drawPath(path, color = Gold, style = Stroke(width = 3f))
                            val di = dragIdx
                            if (di != null) {
                                val cp = c[di]
                                val loupeR = 58.dp.toPx()
                                val cxL = size.width / 2
                                val cyL = loupeR + 10.dp.toPx()
                                val lPath = Path().apply { addOval(Rect(cxL - loupeR, cyL - loupeR, cxL + loupeR, cyL + loupeR)) }
                                clipPath(lPath) {
                                    val srcW = (bmp.width * 0.16f).toInt().coerceAtLeast(10)
                                    val sx = ((cp.x * bmp.width).toInt() - srcW / 2).coerceIn(0, maxOf(0, bmp.width - srcW))
                                    val sy = ((cp.y * bmp.height).toInt() - srcW / 2).coerceIn(0, maxOf(0, bmp.height - srcW))
                                    drawImage(bmp.asImageBitmap(),
                                        srcOffset = IntOffset(sx, sy),
                                        srcSize = IntSize(srcW, srcW),
                                        dstOffset = IntOffset((cxL - loupeR).toInt(), (cyL - loupeR).toInt()),
                                        dstSize = IntSize((loupeR * 2).toInt(), (loupeR * 2).toInt()))
                                }
                                drawCircle(Color.White, loupeR, Offset(cxL, cyL), style = Stroke(width = 3f))
                                drawLine(Gold, Offset(cxL - 13, cyL), Offset(cxL + 13, cyL), strokeWidth = 2.5f)
                                drawLine(Gold, Offset(cxL, cyL - 13), Offset(cxL, cyL + 13), strokeWidth = 2.5f)
                            }
                        }
                        c.forEachIndexed { idx, p ->
                            Box(
                                Modifier
                                    .offset(x = (offX + p.x * dispW - 21).dp, y = (offY + p.y * dispH - 21).dp)
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .pointerInput(idx) {
                                        detectDragGestures(
                                            onDragStart = { dragIdx = idx },
                                            onDragEnd = { dragIdx = null },
                                            onDragCancel = { dragIdx = null }
                                        ) { change, drag ->
                                            change.consume()
                                            val cur = corners ?: return@detectDragGestures
                                            val next = Array(4) { PointF(cur[it].x, cur[it].y) }
                                            next[idx] = PointF(
                                                (cur[idx].x + drag.x / (dispW * density)).coerceIn(0f, 1f),
                                                (cur[idx].y + drag.y / (dispH * density)).coerceIn(0f, 1f)
                                            )
                                            corners = next
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
                                    Text("✥", color = GoldInk, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            if (mode == "preview") "This is your flattened page."
            else if (detected) "Edges found — drag the corners to adjust."
            else "No clear edges found — drag the corners onto your document.",
            color = Color(0xFFCFC8BA), fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rotate:", color = Color(0xFFCFC8BA), fontSize = 12.5.sp)
            OutlinedButton(onClick = { rotateBy(270) }, enabled = !busy && original != null, shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) { Text("⟲ Left", fontSize = 12.5.sp) }
            OutlinedButton(onClick = { rotateBy(90) }, enabled = !busy && original != null, shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) { Text("Right ⟳", fontSize = 12.5.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "adjust",
                onClick = { mode = "adjust" },
                label = { Text("Adjust corners", fontSize = 12.5.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = GoldInk, containerColor = Color(0xFF262019), labelColor = Color(0xFFE8E2D4)),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = mode == "adjust", selectedBorderColor = Gold, borderColor = Color(0xFF4A4234), selectedBorderWidth = 1.dp, borderWidth = 1.dp)
            )
            FilterChip(
                selected = mode == "preview",
                onClick = { mode = "preview" },
                label = { Text("Preview", fontSize = 12.5.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = GoldInk, containerColor = Color(0xFF262019), labelColor = Color(0xFFE8E2D4)),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = mode == "preview", selectedBorderColor = Gold, borderColor = Color(0xFF4A4234), selectedBorderWidth = 1.dp, borderWidth = 1.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(
                Imaging.ScanFilter.ORIGINAL to "Original",
                Imaging.ScanFilter.MAGIC to "Magic Color",
                Imaging.ScanFilter.GRAY to "Gray",
                Imaging.ScanFilter.BW to "B&W"
            )
            options.forEach { (f, label) ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(label, fontSize = 12.5.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = GoldInk, containerColor = Color(0xFF262019), labelColor = Color(0xFFE8E2D4)),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == f, selectedBorderColor = Gold, borderColor = Color(0xFF4A4234), selectedBorderWidth = 1.dp, borderWidth = 1.dp)
                )
            }
        }
        // Primary actions live at the BOTTOM now — thumb reach, one hand.
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { processPage { onNext(it) } },
                enabled = !busy && original != null,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) { Text("Next page", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = { processPage { onDone(it) } },
                enabled = !busy && original != null,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) { Text("Done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
