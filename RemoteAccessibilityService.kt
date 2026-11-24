package com.remotephone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Performs the input events sent from the paired viewer.
 * The user must enable this service manually in Settings > Accessibility.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screenW() = resources.displayMetrics.widthPixels.toFloat()
    private fun screenH() = resources.displayMetrics.heightPixels.toFloat()

    fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "tap" -> tap(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
            "swipe" -> swipe(
                msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                msg.optLong("d", 300)
            )
            "key" -> key(msg.optString("k"))
            "text" -> typeText(msg.optString("v"))
        }
    }

    private fun tap(nx: Float, ny: Float) {
        val path = Path().apply { moveTo(nx * screenW(), ny * screenH()) }
        dispatch(path, 50)
    }

    private fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(nx1 * screenW(), ny1 * screenH())
            lineTo(nx2 * screenW(), ny2 * screenH())
        }
        dispatch(path, durationMs.coerceIn(50, 2000))
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun key(k: String) {
        when (k) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "scrollup" -> swipe(0.5f, 0.35f, 0.5f, 0.75f, 300)
            "scrolldown" -> swipe(0.5f, 0.75f, 0.5f, 0.35f, 300)
            "backspace" -> editFocused { it.dropLast(1) }
        }
    }

    private fun typeText(v: String) = editFocused { it + v }

    private fun editFocused(transform: (String) -> String) {
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, transform(current))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
