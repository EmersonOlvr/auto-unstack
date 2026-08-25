package com.autounstack.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the notification shade and auto-expands collapsed notification
 * groups/stacks so you don't lose messages hidden behind "N notifications"
 * badges or a bundled "show latest message only" conversation.
 *
 * Two different collapsed-group UI patterns exist on One UI 8.5 and both
 * are handled here (see [RowKind]):
 *
 *  - STACK: several DIFFERENT notifications from the same app collapsed
 *    into one summary card with a numeric child-count bubble
 *    (resource-id android:id/group_child_count_number, e.g. "3").
 *  - CONVERSATION: a single MessagingStyle notification (WhatsApp, SMS,
 *    Telegram...) showing only the latest message, expandable via its own
 *    android:id/expand_button.
 *
 * Ordinary single notifications (a plain system alert, a calendar
 * reminder, etc.) are intentionally left alone even though they also have
 * an expand_button — we only touch rows that look like a real group/stack,
 * to avoid force-expanding things the user never complained about.
 */
class NotificationExpandService : AccessibilityService() {

    private val TAG = "NotificationExpandService"

    // Minimum time between two performAction() calls, just to avoid
    // hammering the UI if several events land back-to-back for the same
    // content change. This is per-attempt, not a one-shot gate — it never
    // blocks a *different* group from being expanded.
    private val CLICK_COOLDOWN_MS = 150L

    // After the shade opens, notification rows can still be laying
    // themselves out (badge/count text populated a moment later, or the
    // open animation still running), and on some ROMs the follow-up
    // content-changed events don't reliably reach us. To stay robust we
    // don't rely on a single scan: we rescan a few times shortly after the
    // shade opens, in addition to reacting to every qualifying event.
    private val RESCAN_DELAYS_MS = longArrayOf(150L, 350L, 650L, 1100L)

    private val mainHandler = Handler(Looper.getMainLooper())
    // Scheduled rescans are always treated as "trustworthy" for the
    // reset decision (see scanShade) — if the shade is genuinely gone by
    // the time one fires, that's real, not an animation blip.
    private val rescanRunnable = Runnable { scanShade(triggeredByWindowStateChange = true) }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var keyguardManager: KeyguardManager

    // Notifications we've already attempted to expand during the CURRENT
    // shade-open session, keyed by a content-based identity (see
    // buildRowKey) rather than on-screen bounds. Bounds shift every time a
    // row above/below is expanded or collapsed, which was the root cause
    // of groups getting force-re-expanded right after the user manually
    // collapsed them: the stale bounds-based key no longer matched, so the
    // row looked "new" again. A key derived from the row's source package
    // + its own title text stays stable across that kind of reflow, so
    // once we've handled a group this session we leave it alone for good
    // — including if the user collapses it back afterwards.
    private val handledRowKeysThisSession = mutableSetOf<String>()
    private var lastClickTime = 0L

    private enum class RowKind { STACK, CONVERSATION, NONE }

    private enum class AttemptResult { CLICKED, ALREADY_EXPANDED, DEFERRED, NOT_APPLICABLE }

    // ---------------------------------------------------------------
    // Session bookkeeping
    // ---------------------------------------------------------------

    private fun resetSession(reason: String) {
        if (handledRowKeysThisSession.isNotEmpty()) {
            Log.d(TAG, "Session reset ($reason); forgetting ${handledRowKeysThisSession.size} handled row(s)")
        }
        handledRowKeysThisSession.clear()
        mainHandler.removeCallbacks(rescanRunnable)
    }

    // ---------------------------------------------------------------
    // Tree helpers
    // ---------------------------------------------------------------

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /** True if [node] sits inside another row's children container, i.e. it is a
     *  child notification of an already-unpacked group rather than a top-level row. */
    private fun isNestedInsideChildrenContainer(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        while (current != null) {
            if (current.viewIdResourceName == "com.android.systemui:id/notification_children_container") {
                return true
            }
            current = current.parent
        }
        return false
    }

    /** Collects text from a row's own direct content (title/app name/conversation
     *  sender), without descending into a nested children container — that content
     *  belongs to the child rows, which get their own keys when visited separately. */
    private fun collectOwnText(node: AccessibilityNodeInfo, into: StringBuilder) {
        val rid = node.viewIdResourceName
        if (rid == "android:id/title" ||
            rid == "android:id/app_name_text" ||
            rid == "android:id/message_name" ||
            rid == "android:id/header_text"
        ) {
            val t = node.text
            if (!t.isNullOrBlank()) into.append(t).append('|')
        }
        if (rid == "com.android.systemui:id/notification_children_container") return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectOwnText(child, into)
        }
    }

    private fun buildRowKey(row: AccessibilityNodeInfo): String {
        val text = StringBuilder()
        collectOwnText(row, text)
        val pkg = row.packageName?.toString() ?: "?"
        return "$pkg|$text"
    }

    private fun findDescendant(node: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == resourceId) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDescendant(child, resourceId)
            if (found != null) return found
        }
        return null
    }

    private fun hasDescendant(node: AccessibilityNodeInfo, resourceId: String): Boolean =
        findDescendant(node, resourceId) != null

    /** Reads the numeric child-count badge (e.g. "3") if present anywhere in the row. */
    private fun readGroupChildCount(row: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val badge = findDescendant(row, "android:id/group_child_count_number") ?: return null
        val text = badge.text?.toString()?.trim()
        return if (!text.isNullOrEmpty() && Regex("^[0-9]+$").matches(text)) badge else null
    }

    private fun classifyRow(row: AccessibilityNodeInfo): RowKind {
        if (readGroupChildCount(row) != null) return RowKind.STACK
        if (hasDescendant(row, "android:id/notification_messaging") ||
            hasDescendant(row, "android:id/group_message_container")
        ) {
            return RowKind.CONVERSATION
        }
        // A row that already contains an unpacked children_container is a
        // group that's mid-way through being expanded; its own top-level
        // expand toggle (if still collapsed) is also worth trying.
        if (hasDescendant(row, "com.android.systemui:id/notification_children_container")) {
            return RowKind.STACK
        }
        return RowKind.NONE
    }

    /** True if the node itself (or, per Android's AccessibilityAction API, an
     *  action it exposes) indicates it's currently collapsed and can be expanded.
     *  This is locale-independent, unlike matching on the "Abrir"/"Recolher" text. */
    private fun supportsExpandAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id }

    private fun supportsCollapseAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id }

    // Fallback for OEM widgets that toggle contentDescription instead of
    // exposing ACTION_EXPAND/ACTION_COLLAPSE. Covers the locales most
    // likely to matter; harmless if it never matches since it's only a
    // fallback behind the action-list check above.
    private val collapsedHints = listOf("abrir", "expandir", "expand", "open", "mostrar mais", "show more")
    private val expandedHints = listOf("recolher", "collapse", "fechar", "close", "mostrar menos", "show less")

    private fun looksCollapsedByContentDesc(node: AccessibilityNodeInfo): Boolean? {
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: return null
        if (desc.isEmpty()) return null
        if (expandedHints.any { desc.contains(it) }) return false
        if (collapsedHints.any { desc.contains(it) }) return true
        return null
    }

    // ---------------------------------------------------------------
    // Click attempt
    // ---------------------------------------------------------------

    private fun attemptExpand(row: AccessibilityNodeInfo, kind: RowKind): AttemptResult {
        val target: AccessibilityNodeInfo
        val useExpandAction: Boolean

        when (kind) {
            RowKind.CONVERSATION -> {
                val expandButton = findDescendant(row, "android:id/expand_button")
                if (expandButton == null) {
                    Log.d(TAG, "Conversation row has no expand_button; skipping")
                    return AttemptResult.NOT_APPLICABLE
                }
                val collapsed = when {
                    supportsExpandAction(expandButton) -> true
                    supportsCollapseAction(expandButton) -> false
                    else -> looksCollapsedByContentDesc(expandButton) ?: true
                }
                if (!collapsed) {
                    Log.d(TAG, "Conversation already expanded; skipping")
                    return AttemptResult.ALREADY_EXPANDED
                }
                target = findClickableSelfOrAncestor(expandButton) ?: expandButton
                useExpandAction = supportsExpandAction(expandButton)
            }
            RowKind.STACK -> {
                val countBadge = readGroupChildCount(row)
                if (countBadge != null) {
                    val clickable = findClickableSelfOrAncestor(countBadge)
                        ?: return AttemptResult.NOT_APPLICABLE
                    target = clickable
                    useExpandAction = supportsExpandAction(target)
                } else {
                    val expandButton = findDescendant(row, "android:id/expand_button")
                    if (expandButton == null || !supportsExpandAction(expandButton)) {
                        Log.d(TAG, "Stack row has nothing collapsible left; skipping")
                        return AttemptResult.NOT_APPLICABLE
                    }
                    target = expandButton
                    useExpandAction = true
                }
            }
            RowKind.NONE -> return AttemptResult.NOT_APPLICABLE
        }

        if (SystemClock.uptimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) {
            // IMPORTANT: this must NOT be treated as "handled" by the
            // caller — the row genuinely wasn't clicked yet, so it needs
            // to be retried on the very next scan pass (which happens a
            // few hundred ms later via the rescan schedule anyway).
            Log.d(TAG, "Cooldown active, deferring click for this row")
            return AttemptResult.DEFERRED
        }

        val action = if (useExpandAction) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }

        val performed = target.performAction(action)
        Log.d(TAG, "Expand attempt kind=$kind useExpandAction=$useExpandAction performed=$performed")
        if (!performed) return AttemptResult.DEFERRED
        lastClickTime = SystemClock.uptimeMillis()
        return AttemptResult.CLICKED
    }

    // ---------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------

    private fun scanRows(node: AccessibilityNodeInfo) {
        if (node.viewIdResourceName == "com.android.systemui:id/expandableNotificationRow" &&
            !isNestedInsideChildrenContainer(node)
        ) {
            val kind = classifyRow(node)
            if (kind != RowKind.NONE) {
                val key = buildRowKey(node)
                if (key in handledRowKeysThisSession) {
                    Log.d(TAG, "Row already handled this session, leaving as-is: $key")
                } else {
                    when (attemptExpand(node, kind)) {
                        AttemptResult.CLICKED, AttemptResult.ALREADY_EXPANDED -> {
                            // Definitive outcome: either we just expanded it,
                            // or it turned out to already be expanded. Either
                            // way this row is done for the session — including
                            // if the user collapses it back afterwards.
                            handledRowKeysThisSession.add(key)
                        }
                        AttemptResult.DEFERRED -> {
                            // Cooldown or a transient performAction failure —
                            // genuinely not done yet. Do NOT mark as handled,
                            // so the next scan pass (event-driven or one of
                            // the scheduled rescans) retries it. Marking it
                            // here was the bug that made rows revealed by
                            // scrolling get silently skipped forever.
                            Log.d(TAG, "Deferred, will retry: $key")
                        }
                        AttemptResult.NOT_APPLICABLE -> {
                            // No usable expand affordance found on this pass;
                            // cheap enough to just re-check next time rather
                            // than risk permanently ignoring it.
                        }
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanRows(child)
            child.recycle()
        }
    }

    private fun scanShade(triggeredByWindowStateChange: Boolean) {
        if (!preferencesManager.isServiceEnabled()) return
        if (keyguardManager.isKeyguardLocked) {
            resetSession("keyguard locked")
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            if (triggeredByWindowStateChange) {
                resetSession("no active window root")
            } else {
                // Can happen for a split second during a row's own
                // expand/collapse animation. Not a reliable signal the
                // shade actually closed — skip this pass but keep the
                // handled-rows set intact so we don't end up re-clicking
                // something the user just collapsed on purpose.
                Log.d(TAG, "No active window root on a content-changed pass; skipping without resetting")
            }
            return
        }

        if (root.packageName?.toString() != "com.android.systemui") {
            if (triggeredByWindowStateChange) {
                resetSession("active window left SystemUI")
            } else {
                Log.d(TAG, "Active window isn't SystemUI on a content-changed pass; skipping without resetting")
            }
            return
        }

        scanRows(root)
    }

    // ---------------------------------------------------------------
    // AccessibilityService
    // ---------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        // Deliberately NOT filtering on event.packageName here: a
        // notification row's content-changed event is reported under the
        // POSTING app's package (e.g. "com.whatsapp"), not systemui, even
        // though it's part of the shade. The only reliable signal that
        // we're looking at the shade is the ACTIVE WINDOW's package,
        // which scanShade() checks via rootInActiveWindow.
        val isStateChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        scanShade(triggeredByWindowStateChange = isStateChange)

        if (isStateChange || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Freshly scrolled-into-view rows (or a freshly opened shade)
            // may still be laying themselves out when this event fires,
            // and follow-up content-changed events aren't always
            // delivered promptly on every ROM. Queue a few cheap rescans
            // to catch groups that weren't ready on the first pass.
            mainHandler.removeCallbacks(rescanRunnable)
            for (delay in RESCAN_DELAYS_MS) {
                mainHandler.postDelayed(rescanRunnable, delay)
            }
        }
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

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}