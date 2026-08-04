package com.mandopop.traverse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirestoreValuesTest {

    /** Shaped like a real `schedules` document body from the Firestore REST API. */
    private val scheduleFields = JSONObject(
        """
        {
          "cardId": {"stringValue": "02v28c3t8af1cxvwokl8d25u"},
          "template": {"stringValue": "/Mandarin_Blueprint/MSLK Card"},
          "promptNr": {"integerValue": "1"},
          "queue": {"stringValue": "review"},
          "suspended": {"booleanValue": false},
          "dueTime": {"timestampValue": "2026-08-03T23:07:43.466Z"},
          "interval": {"doubleValue": 6.147403568},
          "easeFactor": {"integerValue": "3"},
          "repetitions": {"integerValue": "2"},
          "missing": {"nullValue": null}
        }
        """.trimIndent(),
    )

    @Test
    fun `decodes each typed value`() {
        assertEquals("02v28c3t8af1cxvwokl8d25u", FirestoreValues.string(scheduleFields, "cardId"))
        assertEquals(1L, FirestoreValues.long(scheduleFields, "promptNr"))
        assertEquals(false, FirestoreValues.boolean(scheduleFields, "suspended"))
        assertEquals(6.147403568, FirestoreValues.double(scheduleFields, "interval")!!, 1e-9)
    }

    @Test
    fun `reads a whole number written as integerValue into a double field`() {
        // Firestore drops the fractional part on the wire when a double happens to be whole, so
        // easeFactor 3.0 arrives as integerValue "3". Reading it as a double must still work.
        assertEquals(3.0, FirestoreValues.double(scheduleFields, "easeFactor")!!, 1e-9)
    }

    @Test
    fun `parses timestamps including sub-millisecond precision`() {
        assertEquals(
            Instant.parse("2026-08-03T23:07:43.466Z").toEpochMilli(),
            FirestoreValues.timestampMs(scheduleFields, "dueTime"),
        )

        val microseconds = JSONObject(
            """{"updateTime": {"timestampValue": "2026-07-28T23:07:42.101185Z"}}""",
        )
        assertEquals(
            Instant.parse("2026-07-28T23:07:42.101185Z").toEpochMilli(),
            FirestoreValues.timestampMs(microseconds, "updateTime"),
        )
    }

    @Test
    fun `absent and null fields decode to null rather than throwing`() {
        assertNull(FirestoreValues.string(scheduleFields, "nope"))
        assertNull(FirestoreValues.string(scheduleFields, "missing"))
        assertNull(FirestoreValues.long(scheduleFields, "nope"))
        assertNull(FirestoreValues.boolean(scheduleFields, "nope"))
        assertNull(FirestoreValues.timestampMs(scheduleFields, "nope"))
        assertTrue(FirestoreValues.array(scheduleFields, "nope").isEmpty())
    }

    @Test
    fun `counts entries in an events review array`() {
        val fields = JSONObject(
            """
            {
              "review": {"arrayValue": {"values": [
                {"mapValue": {"fields": {"ease": {"integerValue": "3"}}}},
                {"mapValue": {"fields": {"ease": {"integerValue": "4"}}}}
              ]}}
            }
            """.trimIndent(),
        )
        assertEquals(2, FirestoreValues.array(fields, "review").size)
    }

    @Test
    fun `empty events review array counts as zero`() {
        val fields = JSONObject("""{"review": {"arrayValue": {}}}""")
        assertEquals(0, FirestoreValues.array(fields, "review").size)
    }

    @Test
    fun `documentId takes the last path segment`() {
        val name = "projects/alley-d0944/databases/(default)/documents/userNames/abc/events/2026-08-02"
        assertEquals("2026-08-02", FirestoreValues.documentId(name))
        assertNull(FirestoreValues.documentId(null))
    }
}
