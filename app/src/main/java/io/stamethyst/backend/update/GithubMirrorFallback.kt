package io.stamethyst.backend.update

import java.io.IOException

data class GithubMirrorFallbackFailure(
    val source: UpdateSource,
    val error: Throwable
)

data class GithubMirrorFallbackSuccess<T>(
    val source: UpdateSource,
    val value: T
)

class GithubMirrorFallbackException(
    val failures: List<GithubMirrorFallbackFailure>
) : IOException(
    failures.joinToString(separator = " | ") { failure ->
        "${failure.source.displayName}: ${GithubMirrorFallback.summarizeSingleError(failure.error)}"
    }.ifBlank { "No GitHub mirror fallback candidates succeeded." },
    failures.lastOrNull()?.error
)

object GithubMirrorFallback {
    inline fun <T> run(
        preferredUserSource: UpdateSource,
        bypassAcceleratedLinks: Boolean = false,
        block: (UpdateSource) -> T
    ): GithubMirrorFallbackSuccess<T> {
        return run(
            UpdateSource.githubResourceFallbackCandidates(
                preferredUserSource = preferredUserSource,
                bypassAcceleratedLinks = bypassAcceleratedLinks,
            ),
            block,
        )
    }

    /**
     * Walks the candidate mirrors in health order and returns the first success.
     *
     * Ordering is delegated to [GithubMirrorHealthStore] so a mirror that failed
     * moments ago is retried last instead of costing every later call another
     * full connect timeout. Outcomes are reported back so the next call learns
     * from this one.
     */
    inline fun <T> run(
        sources: List<UpdateSource>,
        block: (UpdateSource) -> T
    ): GithubMirrorFallbackSuccess<T> {
        val failures = ArrayList<GithubMirrorFallbackFailure>()
        GithubMirrorHealthStore.orderByHealth(sources).forEach { source ->
            try {
                val value = block(source)
                GithubMirrorHealthStore.recordSuccess(source)
                return GithubMirrorFallbackSuccess(
                    source = source,
                    value = value
                )
            } catch (error: Throwable) {
                GithubMirrorHealthStore.recordFailure(source, error)
                failures += GithubMirrorFallbackFailure(
                    source = source,
                    error = error
                )
            }
        }
        throw GithubMirrorFallbackException(failures)
    }

    fun summarize(error: Throwable): String {
        return when (error) {
            is GithubMirrorFallbackException -> error.message
            else -> summarizeSingleError(error)
        }.orEmpty()
    }

    internal fun summarizeSingleError(error: Throwable): String {
        return error.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error.javaClass.simpleName
    }
}
