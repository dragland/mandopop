package com.mandopop.traverse

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Decoder for Firestore REST's typed-value envelope.
 *
 * REST returns `{"fields": {"interval": {"doubleValue": 6.1}}}` rather than plain JSON, so every
 * field read goes through here. Pure Kotlin so it is unit-testable without a device.
 */
object FirestoreValues {

    fun string(fields: JSONObject, key: String): String? =
        fields.optJSONObject(key)?.let { value ->
            when {
                value.has("stringValue") -> value.optString("stringValue")
                value.has("nullValue") -> null
                else -> null
            }
        }

    fun long(fields: JSONObject, key: String): Long? =
        fields.optJSONObject(key)?.let { value ->
            when {
                value.has("integerValue") -> value.optString("integerValue").toLongOrNull()
                value.has("doubleValue") -> value.optDouble("doubleValue").toLong()
                else -> null
            }
        }

    fun double(fields: JSONObject, key: String): Double? =
        fields.optJSONObject(key)?.let { value ->
            when {
                value.has("doubleValue") -> value.optDouble("doubleValue")
                // Firestore returns whole numbers as integerValue even for double-typed fields.
                value.has("integerValue") -> value.optString("integerValue").toDoubleOrNull()
                else -> null
            }
        }

    fun boolean(fields: JSONObject, key: String): Boolean? =
        fields.optJSONObject(key)?.let { value ->
            if (value.has("booleanValue")) value.optBoolean("booleanValue") else null
        }

    /** Parses a Firestore `timestampValue` (RFC 3339, may carry micro/nanosecond precision). */
    fun timestampMs(fields: JSONObject, key: String): Long? {
        val raw = fields.optJSONObject(key)?.optString("timestampValue")?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    /** Returns the `arrayValue` entries of [key], or an empty list when absent. */
    fun array(fields: JSONObject, key: String): List<JSONObject> {
        val values: JSONArray = fields.optJSONObject(key)
            ?.optJSONObject("arrayValue")
            ?.optJSONArray("values")
            ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }

    /** The trailing path segment of a Firestore document `name`. */
    fun documentId(name: String?): String? =
        name?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
