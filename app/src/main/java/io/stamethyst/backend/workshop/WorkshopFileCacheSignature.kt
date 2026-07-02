package io.stamethyst.backend.workshop

import java.io.File

internal data class WorkshopFileCacheSignature(
    val path: String,
    val length: Long,
    val lastModified: Long,
)

internal fun File.cacheSignature(): WorkshopFileCacheSignature? {
    if (!isFile) return null
    return WorkshopFileCacheSignature(
        path = absolutePath,
        length = length(),
        lastModified = lastModified(),
    )
}
