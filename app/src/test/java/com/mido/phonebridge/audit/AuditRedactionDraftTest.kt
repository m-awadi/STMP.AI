package com.mido.phonebridge.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditRedactionDraftTest {

    private val secrets = listOf("Bearer s3cr3t-bearer", "key-9f8e7d", "sessid=ZZtop42", "abc")

    @Test
    fun `a request with credentials leaves none of them in the serialised record`() {
        val draft = AuditDraft(
            startedAtMs = 1L,
            remote = "100.64.0.1",
            requesterLabelValue = "this device",
            method = "GET",
            path = "/notifications",
            query = mapOf("token" to "abc", "page" to "2"),
            requestHeaders = mapOf(
                "authorization" to secrets[0],
                "x-api-key" to secrets[1],
                "cookie" to secrets[2],
                "host" to "phone:8080",
                "user-agent" to "curl/8",
            ),
            appVersionName = "0",
            appVersionCode = 1,
        )
        draft.outcome(AuditOutcome.DENIED)
        val record = draft.toRecord(2L)

        assertEquals(mapOf("token" to "[redacted]", "page" to "2"), record.query)
        assertEquals(
            mapOf(
                "authorization" to "[redacted]",
                "x-api-key" to "[redacted]",
                "cookie" to "[redacted]",
                "user-agent" to "curl/8",
            ),
            record.requestHeaders,
        )
        val serialised = record.toJson().toString()
        secrets.forEach { assertFalse("leaked $it", serialised.contains(it)) }
        assertFalse(serialised.contains("\"token\":\"abc\""))
    }
}
