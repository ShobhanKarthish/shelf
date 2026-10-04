package com.shobhankarthish.shelf

import android.content.Intent
import android.graphics.Insets
import android.os.Build
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.TextView

class DeskTest : InstrumentationTestCase() {
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())

    /**
     * Typing a book's new name opens the keyboard. That must not rebuild the desk underneath, or the dialog and its
     * field are thrown away and the keyboard closes; the dialog moves up out of the keyboard's way instead.
     */
    fun testKeyboardKeepsTheRenameDialogOpen() {
        if(Build.VERSION.SDK_INT<30) return
        val context=instrumentation.targetContext
        context.getSharedPreferences("shelf",0).edit().putBoolean("onboarded",true).commit()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            ui { views(activity.window.decorView).first { it.contentDescription=="Book options" }.performClick() }
            instrumentation.waitForIdleSync()
            ui { views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("Rename") }.performClick() }
            instrumentation.waitForIdleSync()
            lateinit var field: EditText
            ui { field=views(activity.window.decorView).filterIsInstance<EditText>().single() }
            // The dialog opens the real keyboard itself; let it finish arriving before pretending to be a bigger one.
            Thread.sleep(1500)
            instrumentation.waitForIdleSync()
            var keyboard=0
            ui {
                // What a phone reports as its keyboard slides up: the same bars, with the keyboard over the bottom third.
                val decor=activity.window.decorView
                keyboard=decor.height/3
                decor.dispatchApplyWindowInsets(WindowInsets.Builder(decor.rootWindowInsets)
                    .setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,keyboard)).setVisible(WindowInsets.Type.ime(),true).build())
            }
            Thread.sleep(300)
            instrumentation.waitForIdleSync()
            ui {
                assertTrue("The rename field was thrown away when the keyboard opened",field.isAttachedToWindow)
                assertEquals("The dialog should sit above the keyboard",keyboard,(field.parent.parent as View).paddingBottom)
            }
        } finally { ui { activity.finish() }; instrumentation.waitForIdleSync() }
    }
}
