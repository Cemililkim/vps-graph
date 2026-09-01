package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaddyUpstreamResolverTest {
    private val caddy = DockerContainerInfo("caddy-id", "caddy", ports = emptyList(), networks = listOf("web"))
    private val instance = CaddyInstanceInfo("caddy-id", "caddy")

    @Test fun `dial parser handles names IPv4 and bracketed IPv6 without naive splitting`() {
        assertEquals(CaddyDial("portfolio-web", 3000, CaddyDialKind.DOCKER_NAME), CaddyDialParser.parse("portfolio-web:3000"))
        assertEquals(CaddyDial("127.0.0.1", 8080, CaddyDialKind.LOOPBACK), CaddyDialParser.parse("127.0.0.1:8080"))
        assertEquals(CaddyDial("[::1]", 8080, CaddyDialKind.LOOPBACK), CaddyDialParser.parse("[::1]:8080"))
        assertNull(CaddyDialParser.parse("::1:8080"))
        assertNull(CaddyDialParser.parse("missing-port"))
        assertNull(CaddyDialParser.parse("unsafe value:80"))
    }

    @Test fun `requires matching TCP port and shared Caddy network`() {
        val target = DockerContainerInfo("target", "portfolio-web", ports = listOf(DockerPortInfo("3000", "tcp")), networks = listOf("web"))
        assertEquals(UpstreamResolutionState.RESOLVED, resolve("portfolio-web:3000", target).state)
        assertEquals(UpstreamResolutionState.UNRESOLVED, resolve("portfolio-web:4000", target).state)
        assertEquals(UpstreamResolutionState.UNRESOLVED, resolve("wrong-name:3000", target).state)
        assertEquals(UpstreamResolutionState.UNRESOLVED, resolve("portfolio-web:3000", target.copy(networks = listOf("private"))).state)
        assertEquals(UpstreamResolutionState.UNRESOLVED, resolve("portfolio-web:3000", target.copy(ports = listOf(DockerPortInfo("3000", "udp")))).state)
    }

    @Test fun `unique compose service resolves while duplicates remain ambiguous`() {
        val first = DockerContainerInfo("a", "generated-a", compose = DockerContainerComposeInfo("one", "api"), ports = listOf(DockerPortInfo("4000", "tcp")), networks = listOf("web"))
        assertEquals(UpstreamResolutionState.RESOLVED, resolve("api:4000", first).state)
        val second = first.copy(id = "b", name = "generated-b", compose = DockerContainerComposeInfo("two", "api"))
        assertEquals(UpstreamResolutionState.AMBIGUOUS, resolve("api:4000", first, second).state)
    }

    @Test fun `runtime state does not weaken identity and non Docker targets are never guessed`() {
        val stopped = DockerContainerInfo("target", "api", state = "stopped", ports = listOf(DockerPortInfo("8000", "tcp")), networks = listOf("web"))
        assertEquals(UpstreamResolutionState.RESOLVED, resolve("api:8000", stopped).state)
        assertEquals(UpstreamResolutionState.HOST_TARGET, resolve("host.docker.internal:19999").state)
        listOf("localhost:80", "127.0.0.1:80", "[::1]:80", "10.0.0.4:80").forEach {
            assertEquals(UpstreamResolutionState.UNRESOLVED, resolve(it).state)
        }
        assertEquals(UpstreamResolutionState.UNRESOLVED, CaddyUpstreamResolver.resolve(instance, CaddyUpstreamInfo(), DockerDiscoveryResult(DockerDiscoveryState.AVAILABLE, containers = listOf(caddy))).state)
    }

    private fun resolve(dial: String, vararg containers: DockerContainerInfo): UpstreamResolution = CaddyUpstreamResolver.resolve(
        instance,
        CaddyUpstreamInfo(dial, CaddyUpstreamSourceState.STATIC),
        DockerDiscoveryResult(DockerDiscoveryState.AVAILABLE, containers = listOf(caddy) + containers),
    )
}
