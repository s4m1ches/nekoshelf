package app.meebook.launcher.device

/**
 * What to do about missing storage access (ruling R6). "Denied for good" = asked before and the
 * system no longer shows a rationale. The automatic ask (each process start) never leaves the
 * home screen: when denied for good it does nothing and the card stays. Only the card's
 * «Разрешить» button may fall back to the app's settings page.
 */
object StorageAccess {
    enum class Action { REQUEST, SETTINGS, NONE }

    fun decide(fromCard: Boolean, askedBefore: Boolean, rationale: Boolean): Action {
        val deniedForGood = askedBefore && !rationale
        return when {
            !deniedForGood -> Action.REQUEST
            fromCard -> Action.SETTINGS
            else -> Action.NONE
        }
    }
}
