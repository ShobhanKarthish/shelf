package com.shobhankarthish.shelf

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit

class SelectionTest : InstrumentationTestCase() {
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+60000
        while(SystemClock.uptimeMillis()<deadline) {
            var ready=false; ui { ready=condition() }
            if(ready)return
            Thread.sleep(100)
        }
        fail(label)
    }
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun button(activity: Activity, label: String): Button = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()==label }
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    fun testSelectionUsesAlphaAndPhotoCoordinates() {
        ui {
            val image=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
            val first=Bitmap.createBitmap(30,30,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED); setPixel(15,15,Color.TRANSPARENT) }
            val second=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val stage=SubjectSelectionView(instrumentation.targetContext,image)
            stage.layout(0,0,400,400)
            stage.subjects=listOf(PhotoSubject(first,10,10),PhotoSubject(second,150,60))
            fun at(x: Float, y: Float)=stage.photoToView(x,y).let { stage.subjectAt(it.x,it.y) }
            assertNull(stage.selectedIndex)
            assertEquals(0,at(20f,20f))
            assertEquals(0,at(25.1f,25.1f)) // A hole inside the cut line still picks its object.
            assertEquals(1,at(155f,65f))
            assertNull(at(100f,90f)) // Plain photo between objects picks nothing.
            assertNull(stage.subjectAt(0f,0f)) // The margin around the print is not photo content.
            stage.select(1); assertEquals(1,stage.selectedIndex)
            val exported=stage.subjects[1].export()
            assertEquals(Color.BLUE,exported.getPixel(15,15))
            assertEquals(Color.TRANSPARENT,exported.getPixel(0,0))
            val sticker=stage.sticker(1)
            assertEquals(Color.BLUE,sticker.bitmap.getPixel((10-sticker.left).toInt(),(10-sticker.top).toInt())) // Sticker offsets are in the object's own pixels.
            stage.lockSelection(); stage.select(0); assertEquals(1,stage.selectedIndex)
            stage.dispose()
        }
    }

    private fun touch(stage: View, x: Float, y: Float, hold: Long) {
        val down=SystemClock.uptimeMillis()
        ui { stage.dispatchTouchEvent(MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0)) }
        Thread.sleep(hold)
        ui { stage.dispatchTouchEvent(MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0)) }
    }

    fun testRealModelTapThenSnipAndPersistsOnlySelection() {
        val context=instrumentation.targetContext
        val draft=File(context.cacheDir,"draft.png"); val cutout=File(context.cacheDir,"cutout.png")
        val oldDraft=if(draft.exists())draft.readBytes() else null
        val oldCutout=if(cutout.exists())cutout.readBytes() else null
        val image=instrumentation.context.assets.open("shell.png").use { BitmapFactory.decodeStream(it) }
        var activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            draft.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            ui {
                MainActivity::class.java.getDeclaredField("original").apply { isAccessible=true }.set(activity,image)
                MainActivity::class.java.getDeclaredMethod("process").apply { isAccessible=true }.invoke(activity)
            }
            awaitCondition("Model did not return selectable subjects") { (field(activity,"selectionView") as? SubjectSelectionView)?.subjects?.isNotEmpty()==true }
            lateinit var stage: SubjectSelectionView
            ui {
                stage=field(activity,"selectionView") as SubjectSelectionView
                assertNull(field(activity,"cutout"))
                assertEquals("processing",field(activity,"page"))
                // A single object is chosen for you; with several, nothing is until you tap one.
                if(stage.subjects.size==1) { assertEquals(0,stage.selectedIndex); assertTrue(button(activity,"Snip it out").isEnabled) }
                else { assertNull(stage.selectedIndex); assertFalse(button(activity,"Snip it out").isEnabled) }
            }
            screenshot("selection-photo.png")
            var before: Int?=null
            ui { before=stage.selectedIndex }
            touch(stage,2f,2f,50) // Outside the print: nothing changes.
            ui { assertEquals(before,stage.selectedIndex) }
            // Exercise the actual gesture recognizer with a plain tap on the object's pixels.
            val subject=stage.subjects.first()
            var px=0; var py=0
            search@ for(y in 0 until subject.bitmap.height) for(x in 0 until subject.bitmap.width) {
                if(Color.alpha(subject.bitmap.getPixel(x,y))>240) { px=x; py=y; break@search }
            }
            lateinit var point: android.graphics.PointF
            ui { point=stage.photoToView(subject.left+px+.5f,subject.top+py+.5f) }
            touch(stage,point.x,point.y,40)
            ui { assertEquals(0,stage.selectedIndex); assertTrue(button(activity,"Snip it out").isEnabled) }
            touch(stage,point.x,point.y,ViewConfiguration.getLongPressTimeout().toLong()+200) // Holding still works.
            ui { assertEquals(0,stage.selectedIndex) }
            screenshot("selection-highlight.png")
            ui { button(activity,"Snip it out").performClick(); stage.pause() }
            if(ValueAnimator.areAnimatorsEnabled()) {
                Thread.sleep(3800)
                ui { assertEquals("revealing",field(activity,"page")) }
            }
            ui { stage.resume() }
            if(ValueAnimator.areAnimatorsEnabled()) { Thread.sleep(1200); screenshot("selection-snip.png") }
            awaitCondition("Snip did not reach review") { field(activity,"page")=="review" }
            ui {
                assertTrue(button(activity,"Use this sticker").isEnabled)
                val chosen=field(activity,"cutout") as Bitmap
                assertEquals(subject.bitmap.width+24,chosen.width)
                assertEquals(subject.bitmap.height+24,chosen.height)
                assertEquals(Color.TRANSPARENT,chosen.getPixel(0,0))
            }
            screenshot("selection-final.png")
            assertTrue(cutout.length()>0)
            val stored=BitmapFactory.decodeFile(cutout.path)
            assertEquals(subject.bitmap.width+24,stored.width)
            val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            ui { activity.recreate() }
            val restored=instrumentation.waitForMonitorWithTimeout(monitor,10000)
            instrumentation.removeMonitor(monitor)
            assertNotNull("Draft activity did not recreate",restored)
            activity=restored as MainActivity
            instrumentation.waitForIdleSync()
            ui { assertEquals("review",field(activity,"page")); assertEquals(stored.width,(field(activity,"cutout") as Bitmap).width); assertTrue(button(activity,"Use this sticker").isEnabled) }
            ui { button(activity,"Choose a different object").performClick() }
            awaitCondition("Could not reselect") { (field(activity,"selectionView") as? SubjectSelectionView)?.subjects?.isNotEmpty()==true }
            ui {
                val again=field(activity,"selectionView") as SubjectSelectionView
                if(again.subjects.size==1) assertEquals(0,again.selectedIndex) else assertNull(again.selectedIndex)
                assertEquals(again.selectedIndex!=null,button(activity,"Snip it out").isEnabled)
            }
        } finally {
            ui { activity.finish() }; instrumentation.waitForIdleSync()
            if(oldDraft!=null)draft.writeBytes(oldDraft) else draft.delete()
            if(oldCutout!=null)cutout.writeBytes(oldCutout) else cutout.delete()
        }
    }

    fun testRealModelSeparatesTwoObjects() {
        val source=instrumentation.context.assets.open("shell.png").use { BitmapFactory.decodeStream(it) }
        val image=Bitmap.createBitmap(1100,500,Bitmap.Config.ARGB_8888)
        Canvas(image).apply { drawColor(Color.rgb(246,243,236)); drawBitmap(source,0f,50f,null); drawBitmap(source,580f,50f,null) }
        val client=SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()).build())
        try {
            val result=Tasks.await(client.process(InputImage.fromBitmap(image,0)),60,TimeUnit.SECONDS)
            assertTrue("Expected separate results for two shells, got ${result.subjects.size}",result.subjects.size>=2)
            assertTrue(result.subjects.all { it.bitmap!=null })
        } finally { client.close() }
    }
}
