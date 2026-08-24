package io.stamethyst.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.steamcloud.SteamCloudAcceleratedHttp
import java.io.File
import java.security.MessageDigest
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object RemoteBitmapCacheStore {
    private const val DIRECTORY_NAME = "remote-bitmap-cache"
    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(context: Context, imageUrl: String): Bitmap? {
        val normalizedUrl = imageUrl.trim()
        if (normalizedUrl.isBlank()) {
            return null
        }
        val cacheKey = cacheKey(normalizedUrl)
        memoryCache.get(cacheKey)?.let { return it }
        decodeCached(context, cacheKey)?.let { bitmap ->
            memoryCache.put(cacheKey, bitmap)
            return bitmap
        }
        return download(context, normalizedUrl, cacheKey)?.also { bitmap ->
            memoryCache.put(cacheKey, bitmap)
        }
    }

    private fun decodeCached(context: Context, cacheKey: String): Bitmap? {
        val file = cacheFile(context, cacheKey)
        if (!file.isFile) {
            return null
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun download(context: Context, imageUrl: String, cacheKey: String): Bitmap? {
        return runCatching {
            val directory = cacheDirectory(context).apply { mkdirs() }
            val outputFile = cacheFile(context, cacheKey)
            val tempFile = File(directory, "$cacheKey.tmp")
            val client = createClient(context, imageUrl)
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "SlayTheAmethyst-MarkdownImage")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (outputFile.exists() && !outputFile.delete()) {
                return@runCatching null
            }
            if (!tempFile.renameTo(outputFile)) {
                return@runCatching null
            }
            BitmapFactory.decodeFile(outputFile.absolutePath)
        }.getOrNull()
    }

    private fun cacheFile(context: Context, cacheKey: String): File =
        File(cacheDirectory(context), "$cacheKey.img")

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun createClient(context: Context, imageUrl: String) =
        if (isSteamUrl(imageUrl)) {
            SteamCloudAcceleratedHttp.createClient(
                context = context,
                connectTimeoutMs = CONNECT_TIMEOUT_MS.toLong(),
                readTimeoutMs = READ_TIMEOUT_MS.toLong(),
                callTimeoutMs = READ_TIMEOUT_MS.toLong() + CONNECT_TIMEOUT_MS.toLong(),
            )
        } else {
            WattToolkitAcceleratedHttp.createClient(
                context = context,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                followRedirects = true,
            )
        }

    private fun isSteamUrl(imageUrl: String): Boolean {
        val host = imageUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "steamcommunity.com" ||
            host.endsWith(".steamcommunity.com") ||
            host == "steampowered.com" ||
            host.endsWith(".steampowered.com") ||
            host == "steamusercontent.com" ||
            host.endsWith(".steamusercontent.com") ||
            host.endsWith(".steamstatic.com") ||
            host.endsWith(".akamaihd.net")
    }

    private fun cacheKey(imageUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(imageUrl.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
