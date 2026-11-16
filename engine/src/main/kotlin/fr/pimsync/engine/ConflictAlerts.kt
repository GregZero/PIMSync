package fr.pimsync.engine

data class AlertDecision(val notify: Boolean, val cancel: Boolean, val remember: Set<String>)

/** Decides when a phone should raise a notification about contacts in conflict. */
object ConflictAlerts {
    /**
     * Notifies only when a contact enters a conflict the phone has not announced yet, so that repeated
     * synchronisations stay silent. When the phone is not allowed to notify, new conflicts are not
     * remembered, so they are announced as soon as notifications work again.
     */
    fun decide(previouslyNotified: Set<String>, current: Set<String>, canNotify: Boolean): AlertDecision {
        val fresh = current - previouslyNotified
        val remember = if (fresh.isNotEmpty() && !canNotify) previouslyNotified.intersect(current) else current
        return AlertDecision(notify = fresh.isNotEmpty() && canNotify, cancel = current.isEmpty(), remember = remember)
    }
}
