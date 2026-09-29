package com.shobhankarthish.shelf

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.AndroidTestCase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Region

class ShelfTest : AndroidTestCase() {
    fun testCutoutAndPersistence() {
        val b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        assertNull(cropSubject(b))
        b.eraseColor(Color.RED)
        assertNull(cropSubject(b))
        b.eraseColor(Color.TRANSPARENT)
        for(x in 30..60) for(y in 30..60) b.setPixel(x,y,Color.RED)
        val cropped=cropSubject(b)!!
        assertTrue(cropped.width<100)
        assertEquals(Color.TRANSPARENT,cropped.getPixel(0,0))
        val name="test-shelf.db"; context.deleteDatabase(name)
        var db=ShelfStore(context,name)
        val summer=db.addBook("Summer",Paper.GRID)
        val object1=db.save(cropped,"A little shell","A day at the beach",summer.id,false,photo=b)
        val object2=db.save(b,"A photo","",summer.id,true)
        db.close()
        db=ShelfStore(context,name)
        assertEquals(object1,db.all().find { it.id==object1.id })
        assertTrue(db.image(object1.id).length()>0)
        assertTrue(db.photo(object1.id).length()>0) // The whole photo a sticker came from is kept for its page.
        assertFalse(db.photo(object2.id).exists())
        assertTrue(db.find(object2.id)!!.framed)
        try { db.save(b," ","",summer.id,false); fail("Blank title accepted") } catch(expected:IllegalArgumentException) {}

        val placed=Placement(100f,200f,5f,150f,2)
        db.place(object1.id,placed); assertEquals(placed,db.find(object1.id)!!.place)
        val home=db.addBook("Home",Paper.CHECKER)
        db.update(object1.id,"Shell","",home.id)
        val moved=db.find(object1.id)!!
        assertEquals(home.id,moved.book); assertEquals("Shell",moved.title); assertNull(moved.place) // A new book, a new place.
        db.renameBook(home.id,"Our flat"); db.setPaper(home.id,Paper.DOTS)
        assertEquals(Book(home.id,"Our flat",Paper.DOTS,home.created),db.books().find { it.id==home.id })
        assertEquals(listOf(object2.id),db.deleteBook(summer.id))
        assertFalse(db.image(object2.id).exists())
        db.delete(object1.id)
        assertFalse(db.image(object1.id).exists()); assertFalse(db.photo(object1.id).exists())
        assertTrue(db.all().isEmpty())
        db.close(); context.deleteDatabase(name)
    }

    fun testShelvesBecomeBooks() {
        val name="migration-test.db"; context.deleteDatabase(name)
        val file=context.getDatabasePath(name); file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE objects (id TEXT PRIMARY KEY, title TEXT NOT NULL, story TEXT NOT NULL, shelf TEXT NOT NULL, framed INTEGER NOT NULL, created INTEGER NOT NULL)")
            listOf(arrayOf("a","Shell","Summer",1000L),arrayOf("b","Key","Home",2000L),arrayOf("c","Ticket","Summer",3000L)).forEach { (id,title,shelf,created) ->
                db.insertOrThrow("objects",null,ContentValues().apply { put("id",id as String); put("title",title as String); put("story",""); put("shelf",shelf as String); put("framed",0); put("created",created as Long) })
            }
            db.version=1
        }
        val store=ShelfStore(context,name)
        val books=store.books()
        assertEquals(listOf("Summer","Home"),books.map { it.name })
        assertEquals(listOf(Paper.GRID,Paper.DOTS),books.map { it.paper })
        assertEquals(listOf("a","c"),store.inBook(books[0].id).map { it.id })
        assertEquals(listOf("b"),store.inBook(books[1].id).map { it.id })
        assertTrue(store.all().all { it.place==null && it.aspect==1f }) // No image files here, so the aspect falls back to square.
        store.close(); context.deleteDatabase(name)
    }

    fun testPagesHoldFourThingsInSteadyPlaces() {
        assertEquals(1,PageLayout.pages(0)); assertEquals(1,PageLayout.pages(4)); assertEquals(2,PageLayout.pages(5))
        assertEquals(0,PageLayout.pageOf(3)); assertEquals(1,PageLayout.pageOf(4))
        val item=Keepsake("same-id","Shell","","book",false,0L,1.2f)
        val first=PageLayout.auto(item,0,50f)
        assertEquals(first,PageLayout.auto(item,0,50f)) // The same thing always lands in the same place.
        for(slot in 0 until PageLayout.PER) {
            val p=PageLayout.auto(item,slot,50f)
            assertTrue(p.x-p.size/2>=0f && p.x+p.size/2<=360f); assertTrue(p.y+p.size/2<PageLayout.HEIGHT)
            assertTrue(p.angle in -8f..8f)
        }
    }

    fun testStickerOutlineSurroundsObject() {
        val b=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888)
        for(x in 20..50) for(y in 15..40) b.setPixel(x,y,Color.RED)
        assertNull(traceOutline(Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888),4f,1))
        val outline=traceOutline(b,6f,1)!!
        val region=Region().apply { setPath(outlinePath(outline),Region(-100,-100,200,200)) }
        for(x in 20..50 step 5) for(y in 15..40 step 5) assertTrue("($x,$y) outside the cut",region.contains(x,y))
        assertFalse(region.contains(3,3))
        val sticker=paintSticker(b,outline)
        val edge=sticker.bitmap.getPixel((17-sticker.left).toInt(),(28-sticker.top).toInt())
        assertEquals(255,Color.alpha(edge)); assertTrue(Color.red(edge)>240 && Color.green(edge)>240) // White paper around the object.
        assertEquals(Color.RED,sticker.bitmap.getPixel((30-sticker.left).toInt(),(30-sticker.top).toInt()))
    }
}
