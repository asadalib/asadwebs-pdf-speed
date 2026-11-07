package com.asadwebs.pdfspeed.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A scanned document: an ordered list of page image files on disk. */
data class Doc(
    val id: String,
    val name: String,
    val createdAt: Long,
    val pages: List<String>,
    val starred: Boolean = false,
    val edited: Boolean = false
)

class DocStore(private val context: Context) {
    private val indexFile = File(context.filesDir, "docs.json")
    private val docsDir = File(context.filesDir, "docs").apply { mkdirs() }

    fun load(): List<Doc> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pages = o.getJSONArray("pages")
                Doc(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    createdAt = o.getLong("createdAt"),
                    pages = (0 until pages.length()).map { pages.getString(it) },
                    starred = o.optBoolean("starred"),
                    edited = o.optBoolean("edited")
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(docs: List<Doc>) {
        val arr = JSONArray()
        docs.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("createdAt", d.createdAt)
                put("pages", JSONArray(d.pages)); put("starred", d.starred); put("edited", d.edited)
            })
        }
        indexFile.writeText(arr.toString())
    }

    fun addPageToNewDoc(imageFile: File, name: String): Doc {
        val id = System.currentTimeMillis().toString()
        val dir = File(docsDir, id).apply { mkdirs() }
        val dest = File(dir, "page1.jpg")
        imageFile.copyTo(dest, overwrite = true)
        val doc = Doc(id, name, System.currentTimeMillis(), listOf(dest.absolutePath))
        save(load().filter { it.id != id } + doc)
        return doc
    }

    fun addPage(docId: String, imageFile: File): Doc? {
        val docs = load().toMutableList()
        val idx = docs.indexOfFirst { it.id == docId }
        if (idx < 0) return null
        val d = docs[idx]
        val dest = File(docsDir, "${d.id}/page_${System.currentTimeMillis()}.jpg")
        imageFile.copyTo(dest, overwrite = true)
        val updated = d.copy(pages = d.pages + dest.absolutePath, edited = true)
        docs[idx] = updated; save(docs)
        return updated
    }

    fun toggleStar(docId: String) {
        val docs = load().map { if (it.id == docId) it.copy(starred = !it.starred) else it }
        save(docs)
    }

    fun rename(docId: String, newName: String) {
        val docs = load().map { if (it.id == docId) it.copy(name = newName) else it }
        save(docs)
    }

    fun delete(docId: String) {
        File(docsDir, docId).deleteRecursively()
        save(load().filter { it.id != docId })
    }


    fun addPagesToNewDoc(imageFiles: List<File>, name: String): Doc {
        val id = System.currentTimeMillis().toString()
        val dir = File(docsDir, id).apply { mkdirs() }
        val paths = imageFiles.mapIndexed { i, f ->
            val dest = File(dir, "page${i + 1}.jpg")
            f.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
        val doc = Doc(id, name, System.currentTimeMillis(), paths)
        save(load().filter { it.id != id } + doc)
        return doc
    }

    fun movePage(docId: String, index: Int, dir: Int) {
        val docs = load().toMutableList()
        val idx = docs.indexOfFirst { it.id == docId }
        if (idx < 0) return
        val doc = docs[idx]
        val target = index + dir
        if (index !in doc.pages.indices || target !in doc.pages.indices) return
        val pages = doc.pages.toMutableList()
        val tmp = pages[index]; pages[index] = pages[target]; pages[target] = tmp
        docs[idx] = doc.copy(pages = pages, edited = true)
        save(docs)
    }

    fun deletePage(docId: String, index: Int) {
        val docs = load().toMutableList()
        val idx = docs.indexOfFirst { it.id == docId }
        if (idx < 0) return
        val doc = docs[idx]
        if (index !in doc.pages.indices) return
        File(doc.pages[index]).delete()
        val pages = doc.pages.toMutableList().also { it.removeAt(index) }
        docs[idx] = doc.copy(pages = pages, edited = true)
        save(docs)
    }

    /** Renders every page of a picked PDF to JPEGs and files it as a document. */
    fun importPdf(uri: Uri): Doc? {
        val fd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null } ?: return null
        return try {
            val renderer = PdfRenderer(fd)
            val files = ArrayList<File>()
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = 1400f / page.width
                val bmp = Bitmap.createBitmap(1400, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val f = File(context.cacheDir, "import_${System.currentTimeMillis()}_$i.jpg")
                FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                files.add(f)
            }
            renderer.close(); fd.close()
            if (files.isEmpty()) null
            else addPagesToNewDoc(files, "Imported PDF " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
        } catch (e: Exception) { try { fd.close() } catch (e2: Exception) { }; null }
    }

    /** Renders the document to an A4 PDF in the cache dir and returns the file. */
    fun exportPdf(doc: Doc): File {
        val out = File(context.cacheDir, "${doc.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pdf")
        val pdf = PdfDocument()
        val paint = Paint()
        doc.pages.forEachIndexed { i, path ->
            val bmp = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, i + 1).create()
            val page = pdf.startPage(pageInfo)
            val scale = minOf(595f / bmp.width, 842f / bmp.height)
            val w = bmp.width * scale; val h = bmp.height * scale
            val dest = RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2)
            page.canvas.drawBitmap(bmp, null, dest, paint)
            pdf.finishPage(page)
            bmp.recycle()
        }
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        return out
    }
}
