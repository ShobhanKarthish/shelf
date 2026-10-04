package com.shobhankarthish.shelf

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class Book(val id: String, val name: String, val paper: Paper, val created: Long)

/** Where someone put a thing on its page, in page units (the page is 360 wide). Unarranged things have none. */
data class Placement(val x: Float, val y: Float, val angle: Float, val size: Float, val z: Int)

/**
 * [aspect] is the width over height of the thing's sticker or print, so pages can be laid out before any image loads.
 * [filed] is when it went into its book: when it was kept, or later if it was moved there.
 */
data class Keepsake(val id: String, val title: String, val story: String, val book: String, val framed: Boolean, val created: Long, val aspect: Float, val place: Placement?=null, val favourite: Boolean=false, val filed: Long=created)

class ShelfStore(private val context: Context, name: String="shelf.db") : SQLiteOpenHelper(context, name, null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE objects (id TEXT PRIMARY KEY, title TEXT NOT NULL, story TEXT NOT NULL, shelf TEXT NOT NULL, framed INTEGER NOT NULL, created INTEGER NOT NULL)")
        onUpgrade(db, 1, 4)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if(old<2) {
            db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, name TEXT NOT NULL, paper TEXT NOT NULL, created INTEGER NOT NULL)")
            listOf("book TEXT","aspect REAL","x REAL","y REAL","angle REAL","size REAL","z INTEGER").forEach { db.execSQL("ALTER TABLE objects ADD COLUMN $it") }
            // Each shelf becomes a book, oldest first, and the books take turns with the papers.
            db.rawQuery("SELECT shelf,MIN(created) FROM objects GROUP BY shelf ORDER BY MIN(created)", null).use { c ->
                var i=0
                while(c.moveToNext()) {
                    val id=UUID.randomUUID().toString()
                    db.insertOrThrow("books", null, ContentValues().apply { put("id",id); put("name",c.getString(0)); put("paper",Paper.entries[i++%4].name); put("created",c.getLong(1)) })
                    db.execSQL("UPDATE objects SET book=? WHERE shelf=?", arrayOf(id,c.getString(0)))
                }
            }
            db.rawQuery("SELECT id,framed FROM objects", null).use { c ->
                while(c.moveToNext()) db.execSQL("UPDATE objects SET aspect=? WHERE id=?", arrayOf<Any>(aspectOfFile(image(c.getString(0)),c.getInt(1)==1),c.getString(0)))
            }
        }
        if(old<3) db.execSQL("ALTER TABLE objects ADD COLUMN favourite INTEGER NOT NULL DEFAULT 0")
        if(old<4) db.execSQL("ALTER TABLE objects ADD COLUMN filed INTEGER")
    }

    fun image(id: String)=File(context.filesDir,"$id.png")
    /** The whole photo a sticker was cut from, shown behind it on its own page. */
    fun photo(id: String)=File(context.filesDir,"$id-photo.jpg")

    fun books(): List<Book> = readableDatabase.rawQuery("SELECT id,name,paper,created FROM books ORDER BY created", null).use { c ->
        buildList { while(c.moveToNext()) add(Book(c.getString(0),c.getString(1),Paper.of(c.getString(2)),c.getLong(3))) }
    }
    /** The book everything starts in, made the first time it is needed. */
    fun firstBook(): Book=books().firstOrNull() ?: addBook("My first book",Paper.GINGHAM)
    fun addBook(name: String, paper: Paper): Book {
        require(name.isNotBlank() && name.length<=40)
        val book=Book(UUID.randomUUID().toString(),name.trim(),paper,System.currentTimeMillis())
        writableDatabase.insertOrThrow("books", null, ContentValues().apply { put("id",book.id); put("name",book.name); put("paper",paper.name); put("created",book.created) })
        return book
    }
    fun renameBook(id: String, name: String) { require(name.isNotBlank() && name.length<=40); writableDatabase.update("books",ContentValues().apply { put("name",name.trim()) },"id=?",arrayOf(id)) }
    fun setPaper(id: String, paper: Paper) { writableDatabase.update("books",ContentValues().apply { put("paper",paper.name) },"id=?",arrayOf(id)) }
    /** Deletes the book and everything in it; returns the ids of the things that went with it. */
    fun deleteBook(id: String): List<String> {
        val gone=all().filter { it.book==id }.map { it.id }
        writableDatabase.delete("objects","book=?",arrayOf(id)); writableDatabase.delete("books","id=?",arrayOf(id))
        gone.forEach { image(it).delete(); photo(it).delete() }
        return gone
    }

    fun all(): List<Keepsake> = readableDatabase.rawQuery("SELECT id,title,story,book,framed,created,aspect,x,y,angle,size,z,favourite,filed FROM objects ORDER BY created", null).use { c ->
        buildList { while(c.moveToNext()) {
            val place=if(c.isNull(7)) null else Placement(c.getFloat(7),c.getFloat(8),c.getFloat(9),c.getFloat(10),c.getInt(11))
            add(Keepsake(c.getString(0),c.getString(1),c.getString(2),c.getString(3) ?: "",c.getInt(4)==1,c.getLong(5),if(c.isNull(6)) 1f else c.getFloat(6),place,c.getInt(12)==1,if(c.isNull(13)) c.getLong(5) else c.getLong(13)))
        } }
    }
    /** A book's things in the order they went into it, so one moved in later joins at the end. */
    fun inBook(id: String)=all().filter { it.book==id }.sortedBy { it.filed }
    fun find(id: String)=all().find { it.id==id }

    fun save(bitmap: Bitmap, title: String, story: String, book: String, framed: Boolean, photo: Bitmap?=null): Keepsake {
        require(title.trim().isNotEmpty() && title.length<=120)
        require(story.length<=4000 && book.isNotBlank())
        val item=Keepsake(UUID.randomUUID().toString(),title.trim(),story.trim(),book,framed,System.currentTimeMillis(),aspectOf(bitmap.width,bitmap.height,framed))
        val target=image(item.id); val temp=File(context.filesDir,"${item.id}.tmp")
        val photoTarget=photo(item.id); val photoTemp=File(context.filesDir,"${item.id}-photo.tmp")
        try {
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)); it.fd.sync() }
            check(temp.renameTo(target))
            if(photo!=null) { photoTemp.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG,88,it)); it.fd.sync() }; check(photoTemp.renameTo(photoTarget)) }
            writableDatabase.insertOrThrow("objects", null, ContentValues().apply {
                put("id",item.id); put("title",item.title); put("story",item.story); put("shelf",book); put("book",book)
                put("framed",if(framed) 1 else 0); put("created",item.created); put("aspect",item.aspect)
            })
            return item
        } catch(e: Exception) { temp.delete(); target.delete(); photoTemp.delete(); photoTarget.delete(); throw e }
    }
    /** Moving a thing to another book clears where it was arranged; it takes its turn on the new pages. */
    fun update(id: String, title: String, story: String, book: String) {
        require(title.trim().isNotEmpty() && title.length<=120 && story.length<=4000 && book.isNotBlank())
        val moved=find(id)?.book!=book
        writableDatabase.update("objects",ContentValues().apply {
            put("title",title.trim()); put("story",story.trim()); put("book",book); put("shelf",book)
            if(moved) { listOf("x","y","angle","size","z").forEach { putNull(it) }; put("filed",System.currentTimeMillis()) }
        },"id=?",arrayOf(id))
    }
    fun place(id: String, place: Placement?) {
        writableDatabase.update("objects",ContentValues().apply {
            if(place==null) listOf("x","y","angle","size","z").forEach { putNull(it) }
            else { put("x",place.x); put("y",place.y); put("angle",place.angle); put("size",place.size); put("z",place.z) }
        },"id=?",arrayOf(id))
    }
    fun setFavourite(id: String, favourite: Boolean) { writableDatabase.update("objects",ContentValues().apply { put("favourite",if(favourite) 1 else 0) },"id=?",arrayOf(id)) }
    fun delete(id: String) { writableDatabase.delete("objects","id=?",arrayOf(id)); image(id).delete(); photo(id).delete() }

    companion object {
        /** Width over height of the sticker or print that will be made from an image this size. */
        fun aspectOf(w: Int, h: Int, framed: Boolean): Float {
            if(w<=0 || h<=0) return 1f
            if(framed) { val m=min(w,h); return (w+m*.1f)/(h+m*.21f) }
            val pad=max(5f,max(w,h)*.045f)
            return (w+2*pad)/(h+2*pad)
        }
        private fun aspectOfFile(file: File, framed: Boolean): Float {
            val o=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(file.path,o)
            return aspectOf(o.outWidth,o.outHeight,framed)
        }
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
