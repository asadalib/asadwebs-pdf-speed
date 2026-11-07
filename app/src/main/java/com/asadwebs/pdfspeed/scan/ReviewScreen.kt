package com.asadwebs.pdfspeed.scan

import android.graphics.Bitmap
import android.graphics.PointF
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asadwebs.pdfspeed.ui.Gold
import com.asadwebs.pdfspeed.ui.GoldInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** After-capture review: two modes — Adjust (original photo + draggable
 *  detected corners) and Preview (warped + filtered result). Done re-runs
 *  the warp + filter at full resolution and hands back the finished page. */
@Composable
fun ReviewScreen(imageFile: File, onDone: (File) -> Unit, onRetake: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf<Array<PointF>?>(null) }
    var detected by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(Imaging.ScanFilter.MAGIC) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var mode by remember { mutableStateOf("adjust") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(imageFile) {
        withContext(Dispatchers.IO) {
            val bmp = Imaging.loadOriented(imageFile)
            original = bmp
            val found = Imaging.detectCorners(bmp)
            corners = found ?: arrayOf(
                PointF(0.03f, 0.03f), PointF(0.97f, 0.03f),
                PointF(0.97f, 0.97f), PointF(0.03f, 0.97f)
            )
            detected = found != null
        }
    }

    LaunchedEffect(mode, filter) {
        if (mode != "preview") return@LaunchedEffect
        val bmp = original ?: return@LaunchedEffect
        val c = corners ?: return@LaunchedEffect
        preview = null
        withContext(Dispatchers.IO) {
            val small = Bitmap.createScaledBitmap(bmp, 700, (bmp.height * 700f / bmp.width).toInt().coerceAtLeast(1), true)
            preview = Imaging.applyFilter(Imaging.warp(small, c), filter)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF141210))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetake, enabled = !busy) { Text("Retake", color = Color.White) }
            Text("Review", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val bmp = original ?: return@Button
                    val c = corners ?: return@Button
                    busy = true
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            val done = Imaging.applyFilter(Imaging.warp(bmp, c), filter)
                            val f = File(context.cacheDir, "page_${System.currentTimeMillis()}.jpg")
                            Imaging.saveJpeg(done, f)
                            f
                        }
                        busy = false
                        onDone(outFile)
                    }
                },
                enabled = !busy && original != null,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk)
            ) { Text("Done") }
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
                        }
                        c.forEachIndexed { idx, p ->
                            Box(
                                Modifier
                                    .offset(x = (offX + p.x * dispW - 17).dp, y = (offY + p.y * dispH - 17).dp)
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Gold)
                                    .pointerInput(idx) {
                                        detectDragGestures { change, drag ->
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
                            ) { Text("✥", color = GoldInk, fontSize = 15.sp) }
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
        if (busy) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Gold, modifier = Modifier.size(22.dp))
            }
        }
    }
}
