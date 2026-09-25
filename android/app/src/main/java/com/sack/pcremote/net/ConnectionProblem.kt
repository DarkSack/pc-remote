package com.sack.pcremote.net

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ══════════════════════════════════════════════════════════════
// Por qué no hay conexión, dicho para personas.
//
// "SocketException: ECONNREFUSED" no le sirve a nadie como único mensaje:
// cada fallo lleva un título y una explicación con lo que se puede hacer,
// y el texto técnico queda en `detail` para "Ver detalles".
// ══════════════════════════════════════════════════════════════

data class ConnectionProblem(
    val kind: Kind,
    val title: String,
    val message: String,
    /** The technical text (exception), for "Ver detalles". */
    val detail: String? = null,
) {
    enum class Kind { NO_NETWORK, UNREACHABLE, REFUSED, TIMEOUT, CLOSED, CERTIFICATE, REVOKED, AUTH, BAD_ADDRESS, UNKNOWN }

    /** Retrying on its own would not help (revoked device, wrong certificate…): wait for the user. */
    val isFinal: Boolean get() = kind in FINAL

    companion object {
        private val FINAL = setOf(Kind.CERTIFICATE, Kind.REVOKED, Kind.AUTH, Kind.BAD_ADDRESS)

        fun revoked() = ConnectionProblem(
            Kind.REVOKED, "Dispositivo revocado",
            "Este móvil ya no está autorizado en el PC. Vuelve a emparejarlo desde el panel del agente.",
        )

        fun certificate(detail: String?) = ConnectionProblem(
            Kind.CERTIFICATE, "El certificado del PC cambió",
            "No coincide con el que se guardó al emparejar. Si reinstalaste el agente, vuelve a emparejar el PC.",
            detail,
        )

        fun auth(detail: String?) = ConnectionProblem(
            Kind.AUTH, "El PC rechazó este móvil",
            "La autenticación falló. Vuelve a emparejar el PC desde el panel del agente.",
            detail,
        )

        fun badAddress(host: String) = ConnectionProblem(
            Kind.BAD_ADDRESS, "Dirección no válida",
            "La dirección guardada ($host) no es válida. Corrígela en Ajustes o vuelve a emparejar.",
        )

        fun from(t: Throwable): ConnectionProblem {
            val chain = generateSequence(t) { it.cause }.toList()
            val detail = chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            val text = chain.joinToString(" ") { it.message.orEmpty() }

            return when {
                chain.any { it is CertificateMismatchException } -> certificate(detail)
                chain.any { it is UnknownHostException } -> ConnectionProblem(
                    Kind.UNREACHABLE, "No se encuentra el PC",
                    "El nombre o la dirección del PC no existe en esta red.", detail)
                chain.any { it is NoRouteToHostException } || "EHOSTUNREACH" in text || "ENETUNREACH" in text ->
                    ConnectionProblem(Kind.NO_NETWORK, "Sin ruta hasta el PC",
                        "Comprueba que el móvil está en la misma red Wi-Fi que el PC.", detail)
                "ECONNREFUSED" in text || (chain.any { it is ConnectException } && "refused" in text.lowercase()) ->
                    ConnectionProblem(Kind.REFUSED, "El PC no respondió",
                        "El agente no está abierto en el PC, o el cortafuegos de Windows bloquea el puerto.", detail)
                "didn't receive pong" in text -> ConnectionProblem(
                    Kind.TIMEOUT, "El PC dejó de responder",
                    "La conexión se quedó colgada. Reconectando…", detail)
                chain.any { it is SocketTimeoutException } || "timeout" in text.lowercase() -> ConnectionProblem(
                    Kind.TIMEOUT, "El PC tarda demasiado",
                    "Puede estar apagado, suspendido o en otra red.", detail)
                chain.any { it is ConnectException } -> ConnectionProblem(
                    Kind.UNREACHABLE, "No se pudo conectar",
                    "El PC no está disponible. ¿Está encendido y en la misma red?", detail)
                chain.any { it is SSLException } -> ConnectionProblem(
                    Kind.CLOSED, "Error de conexión segura",
                    "La conexión cifrada con el PC falló. Se reintentará.", detail)
                chain.any { it is EOFException || it is SocketException } -> ConnectionProblem(
                    Kind.CLOSED, "Se cortó la conexión",
                    "La conexión con el PC se interrumpió. Reconectando…", detail)
                else -> ConnectionProblem(
                    Kind.UNKNOWN, "No se pudo conectar",
                    "Algo falló al hablar con el PC. Se reintentará.", detail)
            }
        }
    }
}
