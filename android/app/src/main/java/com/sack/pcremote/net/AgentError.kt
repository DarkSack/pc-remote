package com.sack.pcremote.net

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * An error as the user should read it: what happened and what to do, with the
 * technical message kept apart for "Ver detalles". Nobody should have to
 * decode "ECONNREFUSED" to know the agent is not running.
 */
data class AgentError(
    val title: String,
    val message: String,
    val technical: String? = null,
    /** True when retrying cannot help until something changes (revoked, other certificate). */
    val permanent: Boolean = false,
) {
    companion object {
        fun from(t: Throwable): AgentError {
            val chain = generateSequence(t) { it.cause }.toList()
            val tech = chain.joinToString(" ← ") { "${it::class.java.simpleName}: ${it.message}" }
            fun has(k: Class<*>) = chain.any { k.isInstance(it) }
            val text = chain.joinToString(" ") { it.message.orEmpty() }

            return when {
                has(CertificateMismatchException::class.java) -> AgentError(
                    "Certificado distinto",
                    "El PC presenta un certificado diferente al emparejado. Si reinstalaste el agente, vuelve a emparejar.",
                    tech, permanent = true,
                )
                has(UnknownHostException::class.java) -> AgentError(
                    "No se encuentra el PC",
                    "La dirección guardada ya no existe en esta red.", tech,
                )
                has(NoRouteToHostException::class.java) || text.contains("ENETUNREACH") || text.contains("EHOSTUNREACH") -> AgentError(
                    "Sin ruta hasta el PC",
                    "Comprueba que el móvil esté en la misma Wi-Fi que el PC.", tech,
                )
                has(ConnectException::class.java) || text.contains("ECONNREFUSED") -> AgentError(
                    "El PC no respondió",
                    "¿Está encendido y con PC Remote abierto en la bandeja?", tech,
                )
                has(SocketTimeoutException::class.java) || text.contains("ETIMEDOUT") -> AgentError(
                    "Tiempo de espera agotado",
                    "El PC tarda demasiado en contestar. Puede estar suspendido o la red ir lenta.", tech,
                )
                has(SSLException::class.java) -> AgentError(
                    "Error de conexión segura",
                    "No se pudo establecer la conexión cifrada con el PC.", tech,
                )
                has(EOFException::class.java) || has(SocketException::class.java) || has(IOException::class.java) -> AgentError(
                    "Conexión perdida",
                    "Se cortó la conexión con el PC. Reconectando…", tech,
                )
                else -> AgentError("Algo salió mal", t.message ?: "Error desconocido.", tech)
            }
        }

        val Revoked = AgentError(
            "Móvil no autorizado",
            "El PC revocó este móvil. Vuelve a emparejarlo desde el panel del agente.",
            permanent = true,
        )

        val Unresponsive = AgentError(
            "El PC dejó de responder",
            "La conexión parecía abierta pero no llegaban respuestas. Reconectando…",
            "ping sin pong",
        )

        fun auth(message: String) = AgentError("No se pudo autenticar", message, message, permanent = true)
    }
}

/** A request answered with success=false, or not answered at all. */
class RequestException(val code: String, message: String) : Exception(message)

/** Friendly text for an agent error code (the agent's own message is usually already Spanish for new modules). */
fun friendlyMessage(t: Throwable): String = when (t) {
    is RequestException -> when (t.code) {
        "FEATURE_DISABLED" -> t.message ?: "Función desactivada en el PC."
        "PERMISSION_DENIED" -> t.message ?: "El PC no lo permite."
        "NOT_FOUND" -> t.message ?: "Ya no existe."
        "TIMEOUT" -> "El PC tardó demasiado en responder."
        else -> t.message ?: "Error del PC."
    }
    is IllegalStateException -> "Sin conexión con el PC."
    else -> AgentError.from(t).message
}
