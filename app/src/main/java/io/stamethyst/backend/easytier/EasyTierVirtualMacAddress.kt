package io.stamethyst.backend.easytier

import java.security.MessageDigest

/** Builds a stable, locally administered MAC for server-side room address allocation. */
internal object EasyTierVirtualMacAddress {
    fun fromDeviceId(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.trim().ifBlank { "sts-android" }.toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xfe) or 0x02).toByte()
        return bytes.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }
}
