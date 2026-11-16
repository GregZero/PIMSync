package fr.pimsync.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConflictHeadsTest {
    private fun upsert(device: String, at: Long, id: String = "$device-$at") =
        Operation(1, id, device, "contact-1", OperationKind.UPSERT, VersionVector(mapOf(device to 1L)), "payload $device", at)

    private fun delete(device: String, at: Long) =
        Operation(1, "$device-del-$at", device, "contact-1", OperationKind.DELETE, VersionVector(mapOf(device to 2L)), null, at)

    @Test
    fun `a device shows its own latest version`() {
        val heads = listOf(upsert("phone-a", 100), upsert("phone-b", 900), upsert("phone-a", 300, "phone-a-later"))
        assertEquals("phone-a-later", ConflictHeads.preferredForDisplay(heads, "phone-a")?.operationId)
    }

    @Test
    fun `a device without its own version shows the most recent one`() {
        val heads = listOf(upsert("phone-a", 100), upsert("phone-b", 900))
        assertEquals("phone-b", ConflictHeads.preferredForDisplay(heads, "phone-c")?.deviceId)
    }

    @Test
    fun `deletions are never displayed`() {
        val heads = listOf(delete("phone-a", 500), upsert("phone-b", 100))
        assertEquals("phone-b", ConflictHeads.preferredForDisplay(heads, "phone-a")?.deviceId)
        assertNull(ConflictHeads.preferredForDisplay(listOf(delete("phone-a", 500)), "phone-a"))
    }
}

class RecordPolicyTest {
    private val now = 10_000_000L

    @Test
    fun `an old unreadable file is quarantined`() {
        val old = now - RecordPolicy.QUARANTINE_AFTER_MILLIS - 1
        assertEquals(RecordVerdict.QUARANTINE, RecordPolicy.verdict(true, false, old, now))
    }

    @Test
    fun `a recent unreadable file is retried later`() {
        assertEquals(RecordVerdict.RETRY_LATER, RecordPolicy.verdict(true, false, now - 1_000, now))
    }

    @Test
    fun `input output problems, unknown dates and newer schemas are never quarantined`() {
        val old = now - RecordPolicy.QUARANTINE_AFTER_MILLIS - 1
        assertEquals(RecordVerdict.RETRY_LATER, RecordPolicy.verdict(false, false, old, now))
        assertEquals(RecordVerdict.RETRY_LATER, RecordPolicy.verdict(true, false, 0L, now))
        assertEquals(RecordVerdict.RETRY_LATER, RecordPolicy.verdict(true, true, old, now))
    }
}

class DeviceIdsTest {
    @Test
    fun `suggests tel followed by the last six characters in lower case`() {
        assertEquals("tel-3f9a2c", DeviceIds.suggest("9774D56D6B3F9A2C"))
    }

    @Test
    fun `refuses identifiers that are missing or too short`() {
        assertNull(DeviceIds.suggest(null))
        assertNull(DeviceIds.suggest("abc"))
        assertNull(DeviceIds.suggest("--__"))
    }

    @Test
    fun `suggestions are accepted by the engine`() {
        assertEquals(true, Operation.DEVICE_ID_PATTERN.matches(DeviceIds.suggest("0123456789abcdef")!!))
    }
}
