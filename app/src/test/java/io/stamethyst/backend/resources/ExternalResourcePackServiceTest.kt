package io.stamethyst.backend.resources

import io.stamethyst.backend.update.UpdateSource
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalResourcePackServiceTest {
    @Test
    fun mirrorSwitchController_notifiesPromptAndRecordsSwitchRequest() {
        val controller = ResourcePackDownloadMirrorSwitchController()
        val prompts = ArrayList<ResourcePackSlowDownloadMirrorSwitch?>()
        val listener = { prompt: ResourcePackSlowDownloadMirrorSwitch? ->
            prompts += prompt
        }
        controller.addSlowDownloadListener(listener)

        val initialVersion = controller.switchRequestVersion()
        val prompt = ResourcePackSlowDownloadMirrorSwitch(
            currentSource = UpdateSource.GH_PROXY_VIP,
            nextSource = UpdateSource.GH_LLKK
        )
        controller.publishSlowDownloadPrompt(prompt)

        assertEquals(prompt, prompts.last())
        assertTrue(controller.requestSwitchToNextMirror())
        assertTrue(controller.hasSwitchRequestSince(initialVersion))
        assertEquals(null, prompts.last())

        controller.removeSlowDownloadListener(listener)
    }

    @Test
    fun orderResourcePackDownloadCandidates_prefersReachableFastestLinks() {
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            listOf(
                probe(UpdateSource.GH_PROXY_COM, reachable = false, elapsedNanos = 100, index = 0),
                probe(UpdateSource.GH_PROXY_VIP, reachable = true, elapsedNanos = 500, index = 1),
                probe(UpdateSource.GH_LLKK, reachable = true, elapsedNanos = 200, index = 2),
                probe(UpdateSource.OFFICIAL, reachable = true, elapsedNanos = 200, index = 3),
            )
        )

        assertEquals(
            listOf(UpdateSource.GH_LLKK, UpdateSource.OFFICIAL, UpdateSource.GH_PROXY_VIP),
            ordered.map { it.source }
        )
        assertEquals(listOf(2, 3, 1), ordered.map { it.candidateIndex })
    }

    @Test
    fun orderResourcePackDownloadCandidates_returnsEmptyWhenAllLinksFail() {
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            listOf(
                probe(UpdateSource.GH_PROXY_COM, reachable = false, elapsedNanos = 100, index = 0),
                probe(UpdateSource.GH_PROXY_VIP, reachable = false, elapsedNanos = 200, index = 1),
            )
        )

        assertTrue(ordered.isEmpty())
    }

    private fun probe(
        source: UpdateSource,
        reachable: Boolean,
        elapsedNanos: Long,
        index: Int,
    ): ExternalResourcePackService.ResourcePackLinkProbeResult {
        return ExternalResourcePackService.ResourcePackLinkProbeResult(
            source = source,
            requestUrl = "https://example.com/${source.id}",
            reachable = reachable,
            elapsedNanos = elapsedNanos,
            candidateIndex = index,
            error = if (reachable) null else IOException("failed")
        )
    }
}
