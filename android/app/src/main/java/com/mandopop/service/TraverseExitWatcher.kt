package com.mandopop.service

/**
 * Notices when the user leaves the Traverse app, so the card count can refresh immediately instead
 * of waiting on the periodic worker (which Android's `FLEXIBILITY` constraint and Doze stretch well
 * past its nominal 15 minutes).
 *
 * Pure state machine, no Android types, so the tricky part — deciding what counts as "left" — is
 * testable. Two things must not be mistaken for leaving:
 *
 *  - **Transient system windows.** Pulling the notification shade or showing the keyboard reports a
 *    window change for SystemUI or the IME while Traverse is still the app in use.
 *  - **Brief detours.** Tapping a link and coming straight back should not fire a sync each way,
 *    hence the caller-driven settle delay.
 */
class TraverseExitWatcher {

    private var insideTraverse = false

    /** What the caller should do in response to a window change. */
    enum class Action {
        /** Nothing to do. */
        IGNORE,

        /** Left Traverse — start (or restart) the settle timer before syncing. */
        SCHEDULE_SYNC,

        /** Back in Traverse — cancel any pending settle timer. */
        CANCEL_PENDING,
    }

    fun onForegroundPackage(packageName: String?): Action {
        if (packageName.isNullOrBlank()) return Action.IGNORE
        if (packageName in TRANSPARENT_PACKAGES) return Action.IGNORE

        if (packageName == TRAVERSE_PACKAGE) {
            insideTraverse = true
            return Action.CANCEL_PENDING
        }

        if (!insideTraverse) return Action.IGNORE
        insideTraverse = false
        return Action.SCHEDULE_SYNC
    }

    companion object {
        const val TRAVERSE_PACKAGE = "com.traverse.android"

        /**
         * Packages that can appear over Traverse without the user having actually left: the shade
         * and system dialogs, the keyboard, and our own lookup overlay.
         */
        private val TRANSPARENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.mandopop",
        )
    }
}
