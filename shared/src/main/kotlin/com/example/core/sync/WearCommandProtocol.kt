package com.example.core.sync

object WearCommandProtocol {
    const val COMMAND_PREFIX = "/pomodoro/command/v1/"
    const val ACK_PREFIX = "/pomodoro/command_ack/v1/"
    const val SCHEMA_VERSION = 1
    val paths = setOf("/pomodoro/control", "/panic/trigger", "/panic/resolve", "/incident/mark")

    fun validId(id: String): Boolean = id.length in 1..256 && id.all { it.isLetterOrDigit() || it in "._-" }
}
