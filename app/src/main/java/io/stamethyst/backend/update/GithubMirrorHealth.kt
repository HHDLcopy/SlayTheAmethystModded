package io.stamethyst.backend.update

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Response

/**
 * Raised when a GitHub mirror answered with a non-success status.
 *
 * Carrying the status code lets [GithubMirrorHealthStore] tell a rate limit
 * apart from a broken mirror instead of treating every failure identically.
 */
class GithubMirrorHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    message: String = "HTTP $statusCode",
) : IOException(message)

/**
 * Converts a failed response into a classified error for mirror fallback.
 *
 * Callers used to raise a bare `IOException("HTTP <code>")`, which erased the
 * distinction between a rate limit and a broken mirror and discarded the
 * `Retry-After` hint that GitHub and most proxies send.
 */
internal fun Response.toGithubMirrorHttpException(detail: String? = null): GithubMirrorHttpException {
    val retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull()
    val suffix = detail?.trim()?.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
    return GithubMirrorHttpException(
        statusCode = code,
        retryAfterSeconds = retryAfterSeconds,
        message = "HTTP $code$suffix",
    )
}

/**
 * Process-wide health memory for GitHub mirrors.
 *
 * The candidate order used to be a static enum ordering, so a mirror that had
 * just failed was still attempted first on the next call and every request paid
 * its full connect timeout again. Recording failures here lets [GithubMirrorFallback]
 * push cooling-down mirrors to the back of the chain.
 *
 * Candidates are only reordered, never dropped: a mirror in cooldown is still
 * tried after the healthy ones so a total outage cannot leave the chain empty.
 */
object GithubMirrorHealthStore {
    private val cooldownUntilMsBySourceId = ConcurrentHashMap<String, Long>()

    @Volatile
    private var nowProvider: () -> Long = System::currentTimeMillis

    /**
     * Moves mirrors that are still cooling down to the back, preserving the
     * relative order inside each group so an explicit user preference is kept
     * whenever that mirror is healthy.
     */
    fun orderByHealth(sources: List<UpdateSource>): List<UpdateSource> {
        if (sources.size < 2) {
            return sources
        }
        val now = nowProvider()
        val (coolingDown, healthy) = sources.partition { source -> isCoolingDown(source, now) }
        if (coolingDown.isEmpty()) {
            return sources
        }
        return healthy + coolingDown
    }

    fun recordSuccess(source: UpdateSource) {
        cooldownUntilMsBySourceId.remove(source.id)
    }

    fun recordFailure(source: UpdateSource, error: Throwable) {
        val cooldownMs = cooldownDurationMsFor(error)
        cooldownUntilMsBySourceId[source.id] = nowProvider() + cooldownMs
    }

    fun isCoolingDown(source: UpdateSource): Boolean = isCoolingDown(source, nowProvider())

    private fun isCoolingDown(source: UpdateSource, now: Long): Boolean {
        val cooldownUntilMs = cooldownUntilMsBySourceId[source.id] ?: return false
        if (now >= cooldownUntilMs) {
            cooldownUntilMsBySourceId.remove(source.id)
            return false
        }
        return true
    }

    private fun cooldownDurationMsFor(error: Throwable): Long {
        val httpError = error.findMirrorHttpException() ?: return DEFAULT_COOLDOWN_MS
        val retryAfterMs = httpError.retryAfterSeconds
            ?.takeIf { it > 0L }
            ?.times(1_000L)
            ?.coerceAtMost(MAX_COOLDOWN_MS)
        return when {
            retryAfterMs != null -> retryAfterMs
            httpError.statusCode in RATE_LIMIT_STATUS_CODES -> RATE_LIMIT_COOLDOWN_MS
            else -> DEFAULT_COOLDOWN_MS
        }
    }

    private fun Throwable.findMirrorHttpException(): GithubMirrorHttpException? = when {
        this is GithubMirrorHttpException -> this
        else -> cause?.takeIf { it !== this }?.findMirrorHttpException()
    }

    internal fun resetForTests(nowProvider: () -> Long = System::currentTimeMillis) {
        cooldownUntilMsBySourceId.clear()
        this.nowProvider = nowProvider
    }

    private const val DEFAULT_COOLDOWN_MS = 3L * 60L * 1_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 10L * 60L * 1_000L
    private const val MAX_COOLDOWN_MS = 30L * 60L * 1_000L
    private val RATE_LIMIT_STATUS_CODES = setOf(403, 429)
}
