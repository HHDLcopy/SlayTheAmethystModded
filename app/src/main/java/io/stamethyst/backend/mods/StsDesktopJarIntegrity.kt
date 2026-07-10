package io.stamethyst.backend.mods

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object StsDesktopJarIntegrity {
    const val EXPECTED_SHA1 = "4a97bd28ca20faf01a5f45fb8047e9db7c605b68"

    data class Result(
        val expectedSha1: String,
        val actualSha1: String,
    ) {
        val matchesExpected: Boolean
            get() = actualSha1.equals(expectedSha1, ignoreCase = true)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun inspect(file: File): Result = Result(
        expectedSha1 = EXPECTED_SHA1,
        actualSha1 = sha1Hex(file),
    )

    @JvmStatic
    @Throws(IOException::class)
    fun sha1Hex(file: File): String {
        if (!file.isFile || file.length() == 0L) {
            throw IOException("desktop-1.0.jar is missing or empty")
        }
        val digest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (error: NoSuchAlgorithmException) {
            throw IOException("Failed to initialize SHA-1 digest.", error)
        }
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        val digestBytes = digest.digest()
        return buildString(digestBytes.size * 2) {
            digestBytes.forEach { byte ->
                append("%02x".format(byte.toInt() and 0xff))
            }
        }
    }
}
