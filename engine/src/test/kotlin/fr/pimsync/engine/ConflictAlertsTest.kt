package fr.pimsync.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConflictAlertsTest {
    @Test
    fun `a first conflict is announced and remembered`() {
        val decision = ConflictAlerts.decide(emptySet(), setOf("a"), canNotify = true)
        assertTrue(decision.notify)
        assertFalse(decision.cancel)
        assertEquals(setOf("a"), decision.remember)
    }

    @Test
    fun `the same conflict is not announced twice`() {
        val decision = ConflictAlerts.decide(setOf("a"), setOf("a"), canNotify = true)
        assertFalse(decision.notify)
        assertEquals(setOf("a"), decision.remember)
    }

    @Test
    fun `a new conflict next to an announced one is announced`() {
        val decision = ConflictAlerts.decide(setOf("a"), setOf("a", "b"), canNotify = true)
        assertTrue(decision.notify)
        assertEquals(setOf("a", "b"), decision.remember)
    }

    @Test
    fun `resolving everything cancels the notification and a later conflict is announced again`() {
        val resolved = ConflictAlerts.decide(setOf("a"), emptySet(), canNotify = true)
        assertTrue(resolved.cancel)
        assertFalse(resolved.notify)
        assertEquals(emptySet<String>(), resolved.remember)
        assertTrue(ConflictAlerts.decide(resolved.remember, setOf("a"), canNotify = true).notify)
    }

    @Test
    fun `conflicts found while notifications are blocked are announced once they work again`() {
        val blocked = ConflictAlerts.decide(emptySet(), setOf("a"), canNotify = false)
        assertFalse(blocked.notify)
        assertEquals(emptySet<String>(), blocked.remember)
        assertTrue(ConflictAlerts.decide(blocked.remember, setOf("a"), canNotify = true).notify)
    }
}
