package com.autounstack.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NotificationExpandService : AccessibilityService() {

    private val TAG = "NotificationExpandService"

    // Minimum time after a successful click before we're willing to
    // click again — gives the expand animation time to settle so we
    // don't grab a transient badge mid-animation.
    private val POST_CLICK_SETTLE_MS = 500L

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var keyguardManager: KeyguardManager

    // CHANGED: groups we've already expanded this shade-open session,
    // keyed by stable CONTENT (app name / title text) instead of screen
    // position. Bounds-based keys broke when the user manually collapsed
    // a group: the list reflows, the badge reappears at slightly
    // different coordinates, and the old bounds-based key no longer
    // matched — so the service treated it as a brand-new badge and
    // re-expanded it right after the user collapsed it. Content-based
    // keys survive that reflow, so a group the user (or we) already
    // expanded stays "handled" for the rest of the session even if it
    // gets collapsed again.
    private val handledGroupIdentitiesThisSession = mutableSetOf<String>()
    private var lastClickTime = 0L

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun isNearRightSide(bounds: Rect, screenWidth: Int): Boolean {
        if (bounds.isEmpty) return false
        return bounds.right >= (screenWidth * 0.85f).toInt()
    }

    private fun isNumericBadgeText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim().toString()
        return Regex("^[0-9]+$").matches(trimmed)
    }

    /**
     * Collects text from this node and its descendants (bounded depth)
     * so we can build a content-based identity for a notification group.
     */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) out.add(desc)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, depth + 1)
            child.recycle()
        }
    }

    /**
     * Builds a stable identity for the notification group that owns this
     * clickable node: its resource id (if any) plus its non-numeric text
     * content (app name, sender, title). Deliberately excludes purely
     * numeric strings so a fluctuating unread count doesn't change the
     * identity.
     */
    private fun buildGroupIdentity(clickableParent: AccessibilityNodeInfo): String {
        val idPart = clickableParent.viewIdResourceName ?: ""
        val texts = mutableListOf<String>()
        collectTexts(clickableParent, texts, 0)
        val stableTexts = texts.filter { !isNumericBadgeText(it) }
        return "$idPart|${stableTexts.joinToString("|")}"
    }

    private fun processNode(node: AccessibilityNodeInfo, screenWidth: Int) {
        if (SystemClock.uptimeMillis() - lastClickTime < POST_CLICK_SETTLE_MS) return

        val text = node.text?.toString()
        if (!isNumericBadgeText(text)) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!isNearRightSide(bounds, screenWidth)) return

        val clickableParent = findClickableParent(node) ?: return

        val groupIdentity = buildGroupIdentity(clickableParent)
        if (handledGroupIdentitiesThisSession.contains(groupIdentity)) {
            // Already expanded (or the user collapsed it back down) this
            // session — respect that and don't re-click.
            return
        }

        if (clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickTime = SystemClock.uptimeMillis()
            handledGroupIdentitiesThisSession.add(groupIdentity)
            Log.d(TAG, "Clicked badge: text=$text identity=$groupIdentity (session total=${handledGroupIdentitiesThisSession.size})")
        }
    }

    private fun scanNodeRecursive(node: AccessibilityNodeInfo, screenWidth: Int) {
        processNode(node, screenWidth)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            scanNodeRecursive(child, screenWidth)
            child.recycle()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!preferencesManager.isServiceEnabled()) return

        if (keyguardManager.isKeyguardLocked) {
            handledGroupIdentitiesThisSession.clear()
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            handledGroupIdentitiesThisSession.clear()
            return
        }

        val rootPackage = root.packageName?.toString()
        if (rootPackage != "com.android.systemui") {
            handledGroupIdentitiesThisSession.clear()
            return
        }

        val eventPackage = event.packageName?.toString()
        if (eventPackage != null && eventPackage != "com.android.systemui") {
            handledGroupIdentitiesThisSession.clear()
            return
        }

        val boundsRect = Rect()
        root.getBoundsInScreen(boundsRect)
        val screenWidth = if (boundsRect.width() > 0) boundsRect.width() else resources.displayMetrics.widthPixels

        scanNodeRecursive(root, screenWidth)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferencesManager = PreferencesManager(this)
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        Log.i(TAG, "Accessibility service connected")
    }
}
