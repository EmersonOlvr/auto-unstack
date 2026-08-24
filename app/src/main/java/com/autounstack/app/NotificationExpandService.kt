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
    // CHANGED: lowered from 450ms — this was adding avoidable delay on
    // top of the shade's own opening animation. 120ms is enough to avoid
    // double-firing on the same content-changed event without adding
    // noticeable lag.
    private val GLOBAL_CLICK_COOLDOWN_MS = 120L

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var keyguardManager: KeyguardManager

    // CHANGED: instead of a single boolean that blocks the whole shade
    // session after the first click, we remember which badges (by their
    // on-screen bounds) were already clicked this session. New badges that
    // scroll into view still get expanded; already-clicked ones are skipped
    // so we don't re-click and re-trigger the shade-reopening loop.
    private val clickedBadgeBoundsThisSession = mutableSetOf<String>()
    private var lastGlobalClickTime = 0L

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                Log.d(TAG, "Found clickable parent: class=${current.className} clickable=true")
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
        val isNumeric = Regex("^[0-9]+$").matches(trimmed)
        Log.d(TAG, "Numeric badge text check: text=\"$trimmed\" isNumeric=$isNumeric")
        return isNumeric
    }

    private fun processNode(node: AccessibilityNodeInfo, screenWidth: Int) {
        val text = node.text?.toString()
        if (!isNumericBadgeText(text)) return
        if (!node.isVisibleToUser) {
            Log.d(TAG, "Skipping numeric node because it is not visible: text=$text")
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (!isNearRightSide(bounds, screenWidth)) {
            Log.d(TAG, "Skipping numeric node because it is not near right side: text=$text bounds=$bounds")
            return
        }

        // CHANGED: key each badge by its screen position so we can tell
        // "already handled" badges apart from newly-visible ones after a
        // scroll, instead of relying on one global flag.
        val badgeKey = bounds.flattenToString()
        if (clickedBadgeBoundsThisSession.contains(badgeKey)) {
            Log.d(TAG, "Skipping badge already clicked this session: text=$text bounds=$bounds")
            return
        }

        // CHANGED: cooldown is now per attempt, not a one-shot gate, so it
        // just prevents rapid re-firing while still allowing multiple
        // distinct badges to be expanded in the same shade session.
        if (SystemClock.uptimeMillis() - lastGlobalClickTime < GLOBAL_CLICK_COOLDOWN_MS) {
            Log.d(TAG, "Global cooldown active")
            return
        }

        Log.d(TAG, "Detected numeric badge node: text=$text bounds=$bounds")
        val clickableParent = findClickableParent(node)
        if (clickableParent == null) {
            Log.d(TAG, "No clickable parent found for numeric badge: text=$text bounds=$bounds")
            return
        }

        Log.d(TAG, "Attempting click on clickable parent for numeric badge: text=$text")
        if (clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastGlobalClickTime = SystemClock.uptimeMillis()
            clickedBadgeBoundsThisSession.add(badgeKey)
            Log.d(TAG, "Click performed; badge marked handled (session total=${clickedBadgeBoundsThisSession.size})")
        } else {
            Log.d(TAG, "Click failed for numeric badge: text=$text")
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
        if (!preferencesManager.isServiceEnabled()) {
            Log.d(TAG, "Service disabled; ignoring event")
            return
        }
        if (keyguardManager.isKeyguardLocked) {
            clickedBadgeBoundsThisSession.clear()
            Log.d(TAG, "Keyguard locked; ignoring event")
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            clickedBadgeBoundsThisSession.clear()
            Log.d(TAG, "No active window root; shade session reset")
            return
        }

        val rootPackage = root.packageName?.toString()
        if (rootPackage != "com.android.systemui") {
            if (clickedBadgeBoundsThisSession.isNotEmpty()) {
                Log.d(TAG, "Left SystemUI (root=$rootPackage); shade session reset")
            }
            clickedBadgeBoundsThisSession.clear()
            return
        }

        val eventPackage = event.packageName?.toString()
        if (eventPackage != null && eventPackage != "com.android.systemui") {
            clickedBadgeBoundsThisSession.clear()
            Log.d(TAG, "Ignoring event from package=$eventPackage; shade session reset")
            return
        }

        // CHANGED: we no longer bail out here just because we've already
        // clicked once this session — we keep scanning so newly-visible
        // groups (e.g. after scrolling) can still be expanded.
        val boundsRect = Rect()
        root.getBoundsInScreen(boundsRect)
        val screenWidth = if (boundsRect.width() > 0) boundsRect.width() else resources.displayMetrics.widthPixels

        Log.d(TAG, "SystemUI event; scanning node tree; eventType=${event.eventType}, screenWidth=$screenWidth")
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
