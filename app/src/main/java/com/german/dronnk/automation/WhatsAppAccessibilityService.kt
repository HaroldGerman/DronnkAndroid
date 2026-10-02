package com.german.dronnk.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var pendingMessage: String? = null
        @Volatile private var pendingSince: Long = 0L

        fun queueSend(message: String) {
            pendingMessage = message
            pendingSince = System.currentTimeMillis()
        }

        fun clearPending() {
            pendingMessage = null
            pendingSince = 0L
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clickScheduled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val message = pendingMessage ?: return

        if (System.currentTimeMillis() - pendingSince > 20_000L) {
            clearPending()
            return
        }

        if (!clickScheduled) {
            clickScheduled = true
            handler.postDelayed({
                clickScheduled = false
                val root = rootInActiveWindow ?: return@postDelayed
                if (composerContains(root, message) && clickSend(root)) {
                    clearPending()
                }
            }, 700L)
        }
    }

    private fun composerContains(node: AccessibilityNodeInfo, message: String): Boolean {
        if (node.className?.toString()?.contains("EditText") == true) {
            val text = node.text?.toString().orEmpty()
            if (text.contains(message, ignoreCase = false)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (composerContains(child, message)) return true
        }
        return false
    }

    private fun clickSend(node: AccessibilityNodeInfo): Boolean {
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()
        val looksLikeSend = description == "enviar" || description == "send" ||
            text == "enviar" || text == "send" ||
            (description.contains("enviar") && node.isClickable) ||
            (description.contains("send") && node.isClickable)

        if (looksLikeSend && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickSend(child)) return true
        }
        return false
    }

    override fun onInterrupt() = Unit
}
