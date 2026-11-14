package com.mido.phonebridge.audit

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `contract/audit-redaction.json` is shared with iOS; [AuditRedaction] must match every vector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditRedactionContractTest {

    private val json: JSONObject = run {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "contract/audit-redaction.json").isFile) dir = dir.parentFile
        val root = requireNotNull(dir) { "contract/ not found above user.dir" }
        JSONObject(File(root, "contract/audit-redaction.json").readText(Charsets.UTF_8))
    }

    @Test
    fun `contract has at least 30 vectors and the published constants`() {
        assertTrue(json.getJSONArray("vectors").length() >= 30)
        assertEquals(1, json.getInt("version"))
        assertEquals(AuditRedaction.REDACTED, json.getString("redactedValue"))
        val dropped = json.getJSONArray("droppedHeaders")
        assertEquals(AuditRedaction.DROPPED_HEADERS, (0 until dropped.length()).map { dropped.getString(it) }.toSet())
        val subs = json.getJSONArray("sensitiveKeySubstrings")
        assertEquals(AuditRedaction.SENSITIVE_SUBSTRINGS.toSet(), (0 until subs.length()).map { subs.getString(it) }.toSet())
    }

    @Test
    fun `every vector matches`() {
        val vectors = json.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val key = v.getString("key")
            val input = mapOf(key to v.getString("value"))
            val out = if (v.getString("kind") == "header") AuditRedaction.headers(input) else AuditRedaction.query(input)
            val expected = v.getJSONObject("expected")
            val name = v.getString("name")
            if (expected.getBoolean("kept")) {
                assertEquals(name, mapOf(key to expected.getString("value")), out)
            } else {
                assertFalse(name, out.containsKey(key))
                assertTrue(name, out.isEmpty())
            }
        }
    }
}
