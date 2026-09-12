package com.shobhankarthish.shelf

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import java.io.File
import java.util.UUID

data class Keepsake(val id: String, val title: String, val story: String, val shelf: String, val framed: Boolean, val created: Long)

class ShelfStore(private val context: Context) : SQLiteOpenHelper(context, "shelf.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE objects (id TEXT PRIMARY KEY, title TEXT NOT NULL, story TEXT NOT NULL, shelf TEXT NOT NULL, framed INTEGER NOT NULL, created INTEGER NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun image(id: String) = File(context.filesDir, "$id.png")
    fun all(): List<Keepsake> = readableDatabase.rawQuery("SELECT id,title,story,shelf,framed,created FROM objects ORDER BY created", null).use { c ->
        buildList { while (c.moveToNext()) add(Keepsake(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getLong(5))) }
    }
    fun save(bitmap: Bitmap, title: String, story: String, shelf: String, framed: Boolean): Keepsake {
        require(title.trim().isNotEmpty() && title.length <= 120)
        require(story.length <= 4000 && shelf.trim().isNotEmpty() && shelf.length <= 80)
        val item = Keepsake(UUID.randomUUID().toString(), title.trim(), story.trim(), shelf.trim(), framed, System.currentTimeMillis())
        val target = image(item.id)
        val temp = File(context.filesDir, "${item.id}.tmp")
        try {
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
            check(temp.renameTo(target))
            writableDatabase.insertOrThrow("objects", null, ContentValues().apply {
                put("id", item.id); put("title", item.title); put("story", item.story); put("shelf", item.shelf); put("framed", if (framed) 1 else 0); put("created", item.created)
            })
            return item
        } catch (e: Exception) { temp.delete(); target.delete(); throw e }
    }
}

// Reject empty/full masks and crop transparent margins; never pretend the original is a cutout.
fun cropSubject(bitmap: Bitmap): Bitmap? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    var opaque = 0; var transparent = 0
    pixels.forEachIndexed { i, p ->
        val alpha = p ushr 24
        if (alpha > 128) { opaque++; val x = i % bitmap.width; val y = i / bitmap.width; left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y) }
        if (alpha < 32) transparent++
    }
    if (opaque < pixels.size / 100 || transparent < pixels.size / 100 || right < left) return null
    val pad = 12
    left = maxOf(0, left - pad); top = maxOf(0, top - pad)
    right = minOf(bitmap.width - 1, right + pad); bottom = minOf(bitmap.height - 1, bottom + pad)
    return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
}
