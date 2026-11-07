package com.asadwebs.pdfspeed.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
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
        val dest = File(docsDir, "${d.id}/page${d.pages.size + 1}.jpg")
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
