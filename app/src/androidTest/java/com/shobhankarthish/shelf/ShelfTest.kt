package com.shobhankarthish.shelf

import android.test.AndroidTestCase
import android.graphics.Bitmap
import android.graphics.Color

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
}
