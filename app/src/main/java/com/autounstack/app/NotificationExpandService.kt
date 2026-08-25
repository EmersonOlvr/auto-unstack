package com.autounstack.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NotificationExpandService : AccessibilityService() {

    private val TAG = "NotificationExpandService"

    // Small cooldown just to avoid double-processing the same click
    // across two events fired in very quick succession — no longer a
    // safety net against wrong clicks, since badge detection is now
    // exact (see below).
    private val POST_CLICK_SETTLE_MS = 300L

    // CHANGED: this is the real, exact resource id Samsung uses for the
    // "N" counter badge on a collapsed notification stack — confirmed
    // from a live uiautomator dump of the device. Matching on this
    // instead of "any numeric text near the right edge" means we can
    // never mistake a timestamp, an unrelated per-notification badge, or
    // anything else for a group counter — because nothing else in the
    // tree carries this id.
    private val GROUP_BADGE_RESOURCE_ID_SUFFIX = "id/group_child_count_number"

    // The resource id of the title of the "preview" notification shown
    // on a collapsed stack — used as a stable identity for the group so
    // we recognize "I already expanded this one" even after it gets
    // manually recollapsed and the badge reappears.
    private val PREVIEW_TITLE_RESOURCE_ID_SUFFIX = "id/title"

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var keyguardManager: KeyguardManager

    // Groups already expanded this shade-open session, keyed by the
    // preview title text of the top-of-stack notification at the moment
    // we clicked it. This survives the list reflowing (unlike screen
    // position) and survives manual recollapse (the preview title is the
    // same notification content each time, unless a genuinely new
    // notification arrives and becomes the new preview — in which case
    // treating it as "new" and expanding again is the right call).
    private val handledGroupIdentitiesThisSession = mutableSetOf<String>()
    private var lastClickTime = 0L

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /** Finds the first descendant whose resource-id ends with idSuffix. */
    private fun findDescendantByIdSuffix(node: AccessibilityNodeInfo, idSuffix: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        val rid = node.viewIdResourceName
        if (rid != null && rid.endsWith(idSuffix)) return node
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDescendantByIdSuffix(child, idSuffix, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun buildGroupIdentity(clickableParent: AccessibilityNodeInfo): String {
        val titleNode = findDescendantByIdSuffix(clickableParent, PREVIEW_TITLE_RESOURCE_ID_SUFFIX)
        val titleText = titleNode?.text?.toString()?.trim().orEmpty()
        // Fall back to the resource id + class name if no title was
        // found (shouldn't normally happen for a real group row).
        return if (titleText.isNotEmpty()) {
            "title:$titleText"
        } else {
            "fallback:${clickableParent.viewIdResourceName}:${clickableParent.className}"
        }
    }

    private fun processNode(node: AccessibilityNodeInfo) {
        if (SystemClock.uptimeMillis() - lastClickTime < POST_CLICK_SETTLE_MS) return

        val rid = node.viewIdResourceName ?: return
        if (!rid.endsWith(GROUP_BADGE_RESOURCE_ID_SUFFIX)) return
        if (!node.isVisibleToUser) return

        val clickableParent = findClickableParent(node) ?: return

        val groupIdentity = buildGroupIdentity(clickableParent)
        if (handledGroupIdentitiesThisSession.contains(groupIdentity)) {
            // Already expanded this group this session (or the user
            // collapsed it back down) — leave it alone.
            return
        }

        if (clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickTime = SystemClock.uptimeMillis()
            handledGroupIdentitiesThisSession.add(groupIdentity)
            Log.d(TAG, "Expanded group: identity=$groupIdentity (session total=${handledGroupIdentitiesThisSession.size})")
        }
    }

    private fun scanNodeRecursive(node: AccessibilityNodeInfo) {
        processNode(node)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            scanNodeRecursive(child)
            child.recycle()
        }
    }

    private fun resetShadeSession() {
        if (handledGroupIdentitiesThisSession.isNotEmpty()) {
            Log.d(TAG, "Shade session reset")
        }
        handledGroupIdentitiesThisSession.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!preferencesManager.isServiceEnabled()) return

        if (keyguardManager.isKeyguardLocked) {
            resetShadeSession()
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            resetShadeSession()
            return
        }

        val rootPackage = root.packageName?.toString()
        if (rootPackage != "com.android.systemui") {
            resetShadeSession()
            return
        }

        val eventPackage = event.packageName?.toString()
        if (eventPackage != null && eventPackage != "com.android.systemui") {
            resetShadeSession()
            return
        }

        // CHANGED: no more time window. Precise badge detection +
        // stable content identity mean it's now safe to react to every
        // relevant event for the whole session — scrolling reveals new
        // groups and they get expanded whenever they appear, while
        // recollapsing an already-handled group is correctly recognized
        // and left alone, regardless of timing.
        scanNodeRecursive(root)
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
