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
        @Volatile var connected: Boolean = false
            private set

        fun queueSend(message: String) {
            pendingMessage = message.trim().takeIf { it.isNotBlank() }
            pendingSince = System.currentTimeMillis()
        }

        fun clearPending() {
            pendingMessage = null
            pendingSince = 0L
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var scheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (pendingMessage == null) return
        scheduleAttempt()
    }

    private fun scheduleAttempt() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            attemptSend()
        }, if (retryCount == 0) 550L else 350L)
    }

    private fun attemptSend() {
        val expected = pendingMessage ?: return
        if (System.currentTimeMillis() - pendingSince > 25_000L) {
            clearPending()
            retryCount = 0
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            retryLater()
            return
        }

        val composer = findComposer(root)
        val composerText = composer?.text?.toString().orEmpty()
        val expectedVisible = composerText.contains(expected, ignoreCase = false)
        val hasPreparedText = composerText.isNotBlank()

        // wa.me can populate the composer before Accessibility exposes the exact
        // text. While a Dronnk message is pending, a non-empty composer in the
        // target WhatsApp window is enough to try the real Send control.
        if ((expectedVisible || hasPreparedText) && clickSend(root)) {
            clearPending()
            retryCount = 0
            return
        }

        retryLater()
    }

    private fun retryLater() {
        retryCount++
        if (retryCount > 18) {
            clearPending()
            retryCount = 0
            return
        }
        scheduleAttempt()
    }

    private fun findComposer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName.orEmpty().lowercase()
        val className = node.className?.toString().orEmpty()
        if (
            className.contains("EditText") ||
            viewId.endsWith(":id/entry") ||
            viewId.contains("conversation_entry") ||
            viewId.contains("message_entry")
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findComposer(child)?.let { return it }
        }
        return null
    }

    private fun clickSend(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName.orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()

        val looksLikeSend =
            viewId.endsWith(":id/send") ||
            viewId.contains("send_button") ||
            description == "enviar" || description == "send" ||
            description.startsWith("enviar") || description.startsWith("send") ||
            text == "enviar" || text == "send"

        if (looksLikeSend) {
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null) {
                if (clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                clickable = clickable.parent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickSend(child)) return true
        }
        return false
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
