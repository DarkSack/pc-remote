package com.sack.pcremote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

    private val fp = "07f50eab62227c5c1d6e6f1c7f05d1701d1782362f259676c6e8f8cc3afd3ac2"

    /** Exactly what PanelEndpoints builds: prefix + camelCase JSON, not URL-encoded. */
    private fun qr(json: String) = "pcremote://pair?$json"

    @Test fun `parses the payload the agent panel generates`() {
        val p = QrPayload.parse(qr("""{"v":1,"host":"192.168.1.50","port":47820,"code":"042917","fp":"$fp","name":"PC-SACKITO"}"""))
        assertNotNull(p)
        assertEquals("192.168.1.50", p!!.host)
        assertEquals(47820, p.port)
        assertEquals("042917", p.code) // leading zero preserved
        assertEquals(fp, p.fp)
        assertEquals("PC-SACKITO", p.name)
    }

    @Test fun `tolerates surrounding whitespace and unknown fields`() {
        assertNotNull(QrPayload.parse("  " + qr("""{"v":1,"host":"pc","port":1,"code":"123456","fp":"$fp","extra":true}""") + "\n"))
    }

    @Test fun `blank name falls back to host`() {
        assertEquals("10.0.0.2", QrPayload.parse(qr("""{"v":1,"host":"10.0.0.2","port":47820,"code":"123456","fp":"$fp","name":" "}"""))!!.name)
    }

    @Test fun `rejects other QRs and malformed payloads`() {
        val bad = listOf(
            "https://example.com",
            "pcremote://pair?not-json",
            qr("""{"v":2,"host":"pc","port":47820,"code":"123456","fp":"$fp"}"""),       // unknown version
            qr("""{"v":1,"host":"","port":47820,"code":"123456","fp":"$fp"}"""),         // no host
            qr("""{"v":1,"host":"pc","port":70000,"code":"123456","fp":"$fp"}"""),       // bad port
            qr("""{"v":1,"host":"pc","port":47820,"code":"12345","fp":"$fp"}"""),        // short code
            qr("""{"v":1,"host":"pc","port":47820,"code":"12a456","fp":"$fp"}"""),       // non-digit code
            qr("""{"v":1,"host":"pc","port":47820,"code":"123456","fp":"abc"}"""),       // short fingerprint
            qr("""{"v":1,"host":"pc","port":47820,"code":"123456","fp":"${"z".repeat(64)}"}"""), // not hex
            qr("""{"v":1,"host":"pc","port":47820,"code":"123456"}"""),                  // missing fp
        )
        bad.forEach { assertNull("should reject: $it", QrPayload.parse(it)) }
    }
}
