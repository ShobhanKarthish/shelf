package com.shobhankarthish.shelf

import android.test.InstrumentationTestCase
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.TimeUnit

class ModelTest : InstrumentationTestCase() {
    fun testRealSubjectSegmentation() {
        val bitmap=instrumentation.context.assets.open("shell.png").use { BitmapFactory.decodeStream(it) }
        val segmenter=SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        try {
            val result=Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap,0)),60,TimeUnit.SECONDS)
            val cutout=cropSubject(result.foregroundBitmap!!)!!
            assertTrue(cutout.hasAlpha())
            File(instrumentation.targetContext.filesDir,"model-proof.png").outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { segmenter.close() }
    }
}
