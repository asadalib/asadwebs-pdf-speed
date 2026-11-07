package com.asadwebs.pdfspeed.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** On-device scan imaging: EXIF-correct load, document corner detection,
 *  perspective warp (per-pixel homography, bilinear), and filters.
 *  The detection + warp + magic-colour math mirrors the proven AsadWebs
 *  web scanner engine, ported to Kotlin. */
object Imaging {

    fun loadOriented(file: File): Bitmap {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Could not read the captured image")
        val exif = try { ExifInterface(file.absolutePath) } catch (e: Exception) { null }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        }
        return if (matrix.isIdentity) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    /** Downscales so the long edge is at most maxSide (returns src if already smaller). */
    fun downscaleTo(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    // ---------- corner detection ----------
    // Dual-strategy detector at 480px analysis width:
    //  A) luminance blob (Otsu region -> hull -> quad), both polarities
    //  B) edge region (Sobel edges -> dilate -> background flood fill -> largest
    //     enclosed region -> hull -> quad)
    // Candidates are scored by area x fill x rectangularity; the best wins.

    /** Returns [TL, TR, BR, BL] normalized 0..1, or null when no document quad is found. */
    fun detectCorners(src: Bitmap): Array<PointF>? {
        val dw = 480
        val dh = max(1, (src.height * dw / src.width))
        val small = Bitmap.createScaledBitmap(src, dw, dh, true)
        val px = IntArray(dw * dh)
        small.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (small !== src) small.recycle()
        val n = dw * dh
        var lum = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = 0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)
        }
        val blr = FloatArray(n)
        for (y in 0 until dh) for (x in 0 until dw) {
            var acc = 0f; var cnt = 0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = x + dx; val sy = y + dy
                if (sx < 0 || sy < 0 || sx >= dw || sy >= dh) continue
                acc += lum[sy * dw + sx]; cnt++
            }
            blr[y * dw + x] = acc / cnt
        }
        lum = blr

        var best: Pair<Array<PointF>, Float>? = null
        fun consider(cand: Pair<Array<PointF>, Float>?) {
            if (cand != null && (best == null || cand.second > best!!.second)) best = cand
        }
        consider(blobQuad(lum, dw, dh, false))
        consider(blobQuad(lum, dw, dh, true))
        consider(edgeQuad(lum, dw, dh))

        val quad = best?.first ?: return null
        var cx = 0f; var cy = 0f
        for (p in quad) { cx += p.x; cy += p.y }
        cx /= 4; cy /= 4
        return quad.map { p ->
            PointF((p.x + (cx - p.x) * 0.01f) / dw, (p.y + (cy - p.y) * 0.01f) / dh)
        }.toTypedArray()
    }

    /** Validates an ordered quad (analysis px) and returns it with a score, or null. */
    private fun validateQuad(ord: List<IntArray>, insideCount: (Array<PointF>) -> Int, w: Int, h: Int): Pair<Array<PointF>, Float>? {
        var area = 0f
        for (i in 0 until 4) {
            val a = ord[i]; val b = ord[(i + 1) % 4]
            area += (a[0] * b[1] - b[0] * a[1]).toFloat()
        }
        area = abs(area) / 2
        val frac = area / (w * h)
        if (frac < 0.08f || frac > 0.97f) return null
        var sign = 0
        val sides = DoubleArray(4)
        for (i in 0 until 4) {
            val p0 = ord[i]; val p1 = ord[(i + 1) % 4]; val p2 = ord[(i + 2) % 4]
            val cr = (p1[0] - p0[0]) * (p2[1] - p1[1]) - (p1[1] - p0[1]) * (p2[0] - p1[0])
            if (i == 0) sign = cr
            else if ((cr < 0) != (sign < 0) || abs(cr) < 1) return null
            sides[i] = hypot((p1[0] - p0[0]).toDouble(), (p1[1] - p0[1]).toDouble())
            if (sides[i] < max(w, h) * 0.10) return null
        }
        val quadF = ord.map { PointF(it[0].toFloat(), it[1].toFloat()) }.toTypedArray()
        val fill = insideCount(quadF) / max(1f, area)
        if (fill < 0.50f) return null
        // rectangularity: opposite sides similar + diagonals near-equal (true of a
        // photographed rectangle, not of a random bright blob)
        val d1 = hypot((ord[2][0] - ord[0][0]).toDouble(), (ord[2][1] - ord[0][1]).toDouble())
        val d2 = hypot((ord[3][0] - ord[1][0]).toDouble(), (ord[3][1] - ord[1][1]).toDouble())
        val diagRatio = min(d1, d2) / max(1.0, max(d1, d2))
        val sideRatio = min(min(sides[0], sides[2]) / max(1.0, max(sides[0], sides[2])),
                            min(sides[1], sides[3]) / max(1.0, max(sides[1], sides[3])))
        val rect = (0.5 + 0.5 * diagRatio) * (0.5 + 0.5 * sideRatio)
        return Pair(quadF, (frac * fill * rect).toFloat())
    }

    private fun blobQuad(lum: FloatArray, w: Int, h: Int, inv: Boolean): Pair<Array<PointF>, Float>? {
        val n = w * h
        val hist = FloatArray(256); var sum = 0f
        for (i in 0 until n) {
            var v = lum[i].toInt(); if (v < 0) v = 0; if (v > 255) v = 255
            hist[v]++; sum += v
        }
        var sumB = 0f; var wB = 0f; var maxVar = -1f; var thr = 127
        for (i in 0 until 256) {
            wB += hist[i]; if (wB == 0f) continue
            val wF = n - wB; if (wF == 0f) break
            sumB += i * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; thr = i }
        }
        fun on(x: Int, y: Int): Boolean {
            val l = lum[y * w + x]
            return if (inv) l < thr else l > thr
        }
        val pts = ArrayList<IntArray>(); var cnt = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!on(x, y)) continue
            cnt++
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || !on(x - 1, y) || !on(x + 1, y) || !on(x, y - 1) || !on(x, y + 1))
                pts.add(intArrayOf(x, y))
        }
        if (pts.size < 40 || cnt < n * 0.05 || cnt > n * 0.985) return null
        val hull = convexHull(pts)
        if (hull.size < 4) return null
        val q4 = simplifyTo4(hull) ?: return null
        val ord = orderCorners(q4)
        return validateQuad(ord, { q ->
            var inside = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (!on(x, y)) continue
                var pos = false; var neg = false
                for (i in 0 until 4) {
                    val a = q[i]; val b = q[(i + 1) % 4]
                    val cr2 = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                    if (cr2 > 0) pos = true else if (cr2 < 0) neg = true
                }
                if (!(pos && neg)) inside++
            }
            inside
        }, w, h)
    }

    private fun edgeQuad(lum: FloatArray, w: Int, h: Int): Pair<Array<PointF>, Float>? {
        val n = w * h
        // Sobel magnitude
        val mag = FloatArray(n)
        var maxMag = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] + lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
            val gy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] + lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
            val m = abs(gx) + abs(gy)
            mag[i] = m; if (m > maxMag) maxMag = m
        }
        if (maxMag <= 0f) return null
        var edge = BooleanArray(n) { mag[it] > maxMag * 0.22f }
        // dilate twice to close small gaps in the document outline
        repeat(2) {
            val d = BooleanArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                if (!edge[y * w + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val sx = x + dx; val sy = y + dy
                    if (sx in 0 until w && sy in 0 until h) d[sy * w + sx] = true
                }
            }
            edge = d
        }
        // background = flood fill from the border over non-edge pixels
        val bg = BooleanArray(n)
        val stack = IntArray(n); var sp = 0
        fun push(i: Int) { if (!bg[i] && !edge[i]) { bg[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        // largest connected component of the enclosed (non-background, non-edge) area
        val region = BooleanArray(n) { !bg[it] && !edge[it] }
        val seen = BooleanArray(n)
        var bestComp: ArrayList<IntArray>? = null
        var bestCount = 0
        val compStack = IntArray(n)
        for (start in 0 until n) {
            if (!region[start] || seen[start]) continue
            var csp = 0; compStack[csp++] = start; seen[start] = true
            val boundary = ArrayList<IntArray>(); var count = 0
            while (csp > 0) {
                val i = compStack[--csp]; val x = i % w; val y = i / w; count++
                val isB = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    !region[i - 1] || !region[i + 1] || !region[i - w] || !region[i + w]
                if (isB) boundary.add(intArrayOf(x, y))
                if (x > 0 && region[i - 1] && !seen[i - 1]) { seen[i - 1] = true; compStack[csp++] = i - 1 }
                if (x < w - 1 && region[i + 1] && !seen[i + 1]) { seen[i + 1] = true; compStack[csp++] = i + 1 }
                if (y > 0 && region[i - w] && !seen[i - w]) { seen[i - w] = true; compStack[csp++] = i - w }
                if (y < h - 1 && region[i + w] && !seen[i + w]) { seen[i + w] = true; compStack[csp++] = i + w }
            }
            if (count > n * 0.05 && count > bestCount) {
                bestComp = boundary
                bestCount = count
            }
        }
        val boundary = bestComp ?: return null
        if (boundary.size < 40) return null
        val hull = convexHull(boundary)
        if (hull.size < 4) return null
        val q4 = simplifyTo4(hull) ?: return null
        val ord = orderCorners(q4)
        return validateQuad(ord, { q ->
            var inside = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (!region[y * w + x]) continue
                var pos = false; var neg = false
                for (i in 0 until 4) {
                    val a = q[i]; val b = q[(i + 1) % 4]
                    val cr2 = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                    if (cr2 > 0) pos = true else if (cr2 < 0) neg = true
                }
                if (!(pos && neg)) inside++
            }
            inside
        }, w, h)
    }


    // ---------- perspective warp (port of homography + per-pixel warp) ----------

    private fun homography(dst: Array<PointF>, srcp: Array<PointF>): DoubleArray? {
        val a = Array(8) { DoubleArray(8) }; val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = dst[i].x.toDouble(); val y = dst[i].y.toDouble()
            val sx = srcp[i].x.toDouble(); val sy = srcp[i].y.toDouble()
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * sx, -y * sx); b[i * 2] = sx
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * sy, -y * sy); b[i * 2 + 1] = sy
        }
        val n = 8
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            if (abs(a[piv][c]) < 1e-12) return null
            if (piv != c) {
                val t = a[c]; a[c] = a[piv]; a[piv] = t
                val tb = b[c]; b[c] = b[piv]; b[piv] = tb
            }
            for (r in 0 until n) {
                if (r == c) continue
                val f = a[r][c] / a[c][c]
                if (f == 0.0) continue
                for (rr in c until n) a[r][rr] -= f * a[c][rr]
                b[r] -= f * b[c]
            }
        }
        return DoubleArray(n) { b[it] / a[it][it] }
    }

    /** Warps the quad (normalized 0..1, TL TR BR BL) into a flat rectangle. */
    fun warp(src: Bitmap, corners01: Array<PointF>): Bitmap {
        val sw = src.width; val sh = src.height
        val c = corners01.map { PointF(it.x * sw, it.y * sh) }.toTypedArray()
        val top = hypot((c[1].x - c[0].x).toDouble(), (c[1].y - c[0].y).toDouble())
        val bottom = hypot((c[2].x - c[3].x).toDouble(), (c[2].y - c[3].y).toDouble())
        val left = hypot((c[3].x - c[0].x).toDouble(), (c[3].y - c[0].y).toDouble())
        val right = hypot((c[2].x - c[1].x).toDouble(), (c[2].y - c[1].y).toDouble())
        var outW = max(top, bottom).toInt().coerceAtLeast(1)
        var outH = max(left, right).toInt().coerceAtLeast(1)
        val cap = 2400
        val longest = max(outW, outH)
        if (longest > cap) {
            val s = cap.toDouble() / longest
            outW = (outW * s).toInt().coerceAtLeast(1); outH = (outH * s).toInt().coerceAtLeast(1)
        }
        val dstRect = arrayOf(PointF(0f, 0f), PointF(outW.toFloat(), 0f), PointF(outW.toFloat(), outH.toFloat()), PointF(0f, outH.toFloat()))
        val h = homography(dstRect, c) ?: return src
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)
        val outPx = IntArray(outW * outH)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val den = h[6] * x + h[7] * y + 1.0
                val sx = (h[0] * x + h[1] * y + h[2]) / den
                val sy = (h[3] * x + h[4] * y + h[5]) / den
                outPx[y * outW + x] = bilinear(srcPx, sw, sh, sx, sy)
            }
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun bilinear(px: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        if (fx < 0 || fy < 0 || fx > w - 1 || fy > h - 1) return 0xFF000000.toInt()
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val dx = fx - x0; val dy = fy - y0
        fun chan(c00: Int, c10: Int, c01: Int, c11: Int, shift: Int): Int {
            val v00 = (c00 shr shift) and 0xFF; val v10 = (c10 shr shift) and 0xFF
            val v01 = (c01 shr shift) and 0xFF; val v11 = (c11 shr shift) and 0xFF
            val top = v00 + (v10 - v00) * dx; val bot = v01 + (v11 - v01) * dx
            return (top + (bot - top) * dy).toInt().coerceIn(0, 255)
        }
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        val r = chan(c00, c10, c01, c11, 16)
        val g = chan(c00, c10, c01, c11, 8)
        val b = chan(c00, c10, c01, c11, 0)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---------- filters ----------

    enum class ScanFilter { ORIGINAL, MAGIC, GRAY, BW }

    fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return src
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        when (filter) {
            ScanFilter.MAGIC -> magicStretch(px)
            ScanFilter.GRAY -> for (i in 0 until n) {
                val c = px[i]
                val y = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)).toInt()
                px[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
            ScanFilter.BW -> {
                // Adaptive threshold (local mean via two-pass box blur) - the
                // scanner-style B&W that keeps text under uneven lighting,
                // instead of one global threshold that floods shadows black.
                val lumA = IntArray(n)
                for (i in 0 until n) {
                    val c = px[i]
                    lumA[i] = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)).toInt()
                }
                val blurred = boxBlur(lumA, w, h, max(12, min(w, h) / 10))
                for (i in 0 until n) {
                    val v = if (lumA[i] > blurred[i] - 14) 255 else 0
                    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            ScanFilter.ORIGINAL -> { }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Port of magicEnhance: per-channel 0.7% / 99.3% percentile stretch. */
    private fun magicStretch(px: IntArray) {
        val n = px.size
        val hist = Array(3) { IntArray(256) }
        for (i in 0 until n) {
            val c = px[i]
            hist[0][(c shr 16) and 0xFF]++; hist[1][(c shr 8) and 0xFF]++; hist[2][c and 0xFF]++
        }
        val lo = IntArray(3); val hi = IntArray(3) { 255 }
        val tLo = n * 0.007; val tHi = n * 0.993
        for (ch in 0 until 3) {
            var acc = 0
            for (v in 0 until 256) { acc += hist[ch][v]; if (acc >= tLo) { lo[ch] = v; break } }
            acc = 0
            for (v in 255 downTo 0) { acc += hist[ch][v]; if (acc >= n - tHi) { hi[ch] = v; break } }
            if (hi[ch] - lo[ch] < 24) { lo[ch] = 0; hi[ch] = 255 }
        }
        for (i in 0 until n) {
            val c = px[i]
            val r = stretch((c shr 16) and 0xFF, lo[0], hi[0])
            val g = stretch((c shr 8) and 0xFF, lo[1], hi[1])
            val b = stretch(c and 0xFF, lo[2], hi[2])
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun stretch(v: Int, lo: Int, hi: Int): Int =
        ((v - lo).toFloat() / (hi - lo) * 255).toInt().coerceIn(0, 255)

    /** Separable box blur over an Int luminance plane (edge-clamped). */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(src.size); val out = IntArray(src.size)
        val win = radius * 2 + 1
        for (y in 0 until h) {
            var acc = 0
            for (k in -radius..radius) acc += src[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / win
                val xa = (x - radius).coerceIn(0, w - 1); val xb = (x + radius + 1).coerceIn(0, w - 1)
                acc += src[y * w + xb] - src[y * w + xa]
            }
        }
        for (x in 0 until w) {
            var acc = 0
            for (k in -radius..radius) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / win
                val ya = (y - radius).coerceIn(0, h - 1); val yb = (y + radius + 1).coerceIn(0, h - 1)
                acc += tmp[yb * w + x] - tmp[ya * w + x]
            }
        }
        return out
    }

    /** Rotates a bitmap by 90/180/270 degrees clockwise. */
    fun rotate(src: Bitmap, degreesCW: Int): Bitmap {
        val d = ((degreesCW % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix(); m.postRotate(d.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
