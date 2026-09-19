package com.shobhankarthish.shelf

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
        var db=ShelfStore(context)
        val object1=db.save(cropped,"A little shell","A day at the beach","Summer",false)
        val object2=db.save(b,"A photo","","Summer",true)
        db.close()
        db=ShelfStore(context)
        assertEquals(object1,db.all().find { it.id==object1.id })
        assertTrue(db.image(object1.id).length()>0)
        assertTrue(db.all().find { it.id==object2.id }!!.framed)
        try { db.save(b," ","","Shelf",false); fail("Blank title accepted") } catch(expected:IllegalArgumentException) {}
        db.writableDatabase.delete("objects","id IN (?,?)",arrayOf(object1.id,object2.id))
        db.image(object1.id).delete(); db.image(object2.id).delete(); db.close()
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
