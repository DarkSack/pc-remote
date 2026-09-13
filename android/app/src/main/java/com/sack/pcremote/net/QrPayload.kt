package com.sack.pcremote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ══════════════════════════════════════════════════════════════
// Contenido del QR que genera el panel web del agente:
//
//   pcremote://pair?{"v":1,"host":"192.168.1.50","port":47820,
//                    "code":"123456","fp":"<sha256 hex>","name":"PC"}
//
// El JSON va tal cual detrás del "?" (el agente no lo codifica como
// query string). Traer la huella en el QR es lo importante: con ella
// el emparejamiento verifica el certificado desde el primer byte, en
// vez de fiarse de lo que responda quien conteste en esa IP.
// ══════════════════════════════════════════════════════════════

@Serializable
data class QrPayload(
    val v: Int,
    val host: String,
    val port: Int,
    val code: String,
    val fp: String,
    val name: String = "PC",
) {
    companion object {
        private const val PREFIX = "pcremote://pair?"
        private val json = Json { ignoreUnknownKeys = true }

        /** Null when the text is not a PC Remote pairing QR, or is malformed. */
        fun parse(raw: String): QrPayload? {
            val text = raw.trim()
            if (!text.startsWith(PREFIX)) return null
            val p = runCatching { json.decodeFromString(serializer(), text.removePrefix(PREFIX)) }.getOrNull() ?: return null
            val ok = p.v == 1 &&
                p.host.isNotBlank() && p.host.length <= 253 &&
                p.port in 1..65535 &&
                p.code.length == 6 && p.code.all(Char::isDigit) &&
                p.fp.length == 64 && p.fp.all { it in "0123456789abcdefABCDEF" }
            return if (ok) p.copy(name = p.name.take(64).ifBlank { p.host }) else null
        }
    }
}
