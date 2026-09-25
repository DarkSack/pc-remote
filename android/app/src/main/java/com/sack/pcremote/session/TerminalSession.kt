package com.sack.pcremote.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the terminal screen shows, kept in the PC session so leaving the
 * screen (or switching tabs) does not wipe the output or the history.
 */
class TerminalSession {
    enum class Kind { Command, Out, Err, Info }
    data class Line(val id: Long, val kind: Kind, val text: String)

    val lines = mutableStateListOf<Line>()
    val history = mutableStateListOf<String>()
    var cwd by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)
    /** Cancels the running command's stream (the agent then kills the process tree). */
    var cancel: (() -> Unit)? = null

    private var next = 0L

    fun add(kind: Kind, text: String) {
        lines += Line(next++, kind, text)
        // Bounded: a runaway `dir /s` must not eat the phone's memory.
        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
    }

    fun remember(command: String) {
        history.remove(command)
        history.add(0, command)
        if (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
    }

    fun clear() = lines.clear()

    companion object {
        const val MAX_LINES = 3000
        const val MAX_HISTORY = 50
    }
}
