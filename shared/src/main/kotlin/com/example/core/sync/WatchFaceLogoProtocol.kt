package com.example.core.sync

import java.security.MessageDigest

object WatchFaceLogoProtocol {
    const val PATH = "/pomodoro/watchface/logo/v1"
    const val STATUS_PATH = "/pomodoro/watchface/logo/status/v1"
    const val SCHEMA_VERSION = 1
    const val IMAGE_SIZE = 128
    const val MAX_PNG_BYTES = 256 * 1024

    fun validRevision(value: String): Boolean = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
