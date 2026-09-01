package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HostDockerGraphFactoryTest {
    @Test fun `builds a deterministic real host Docker graph without mock nodes`() {
        val docker = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-success.json")).readText())
        val discovery = HostDiscoveryResult(HostInfo("vps-prod-01", "Debian", "13", "Linux", "6.1", "x86_64"), docker)
        val target = SshTarget("vps.example", 22, "vpsgraph", "/keys/vps")

        val graph = HostDockerGraphFactory.create(discovery, target)
        val repeat = HostDockerGraphFactory.create(discovery, target)

        assertEquals(graph, repeat)
        assertEquals(1, graph.nodes.count { it.type == NodeType.SERVER })
        assertEquals(1, graph.nodes.count { it.type == NodeType.DOCKER_ENGINE })
        assertEquals(2, graph.nodes.count { it.type == NodeType.DOCKER_COMPOSE_PROJECT })
        assertEquals(3, graph.nodes.count { it.type == NodeType.DOCKER_CONTAINER })
        assertEquals(3, graph.nodes.count { it.type == NodeType.NETWORK })
        assertEquals(3, graph.nodes.count { it.type == NodeType.PORT })
        assertEquals(2, graph.nodes.count { it.type == NodeType.MOUNT })
        assertTrue(graph.edges.any { it.relation == RelationType.RUNS })
        assertEquals(5, graph.edges.count { it.relation == RelationType.CONNECTED_TO })
        assertEquals(2, graph.edges.count { it.relation == RelationType.MOUNTS })
        assertFalse(graph.nodes.any { it.id.startsWith("server:production-vps") || it.label == "Caddy" })
        assertTrue(graph.nodes.filter { it.type == NodeType.DOCKER_CONTAINER }.all { it.metadata["container id"]?.length == 12 })
        val frontendJson = Json.encodeToString(graph)
        listOf("DATABASE_URL", "Password", "Token", "Secret", "Cmd", "Entrypoint", "Labels", "secret").forEach {
            assertFalse(frontendJson.contains(it))
        }
    }

    @Test fun `keeps a real host graph when Docker helper is unavailable`() {
        val discovery = HostDiscoveryResult(HostInfo("vps"), DockerDiscoveryResult(DockerDiscoveryState.HELPER_NOT_INSTALLED))
        val graph = HostDockerGraphFactory.create(discovery, SshTarget("vps.example", 22, "vpsgraph", "/keys/vps"))

        assertEquals(2, graph.nodes.size)
        assertEquals("Docker helper not installed", graph.nodes.single { it.type == NodeType.DOCKER_ENGINE }.metadata["discovery status"])
    }

    @Test fun `builds stable Caddy domain upstream relationships without duplicate containers`() {
        val docker = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-caddy-realistic.json")).readText())
        val discovery = HostDiscoveryResult(HostInfo("vps"), docker)
        val target = SshTarget("vps.example", 22, "vpsgraph", "/keys/vps")
        val graph = HostDockerGraphFactory.create(discovery, target)

        assertEquals(graph, HostDockerGraphFactory.create(discovery, target))
        assertEquals(5, graph.nodes.count { it.type == NodeType.DOCKER_CONTAINER })
        assertEquals(1, graph.nodes.count { it.type == NodeType.REVERSE_PROXY })
        assertEquals(7, graph.nodes.count { it.type == NodeType.DOMAIN })
        assertEquals(6, graph.nodes.count { it.type == NodeType.UPSTREAM })
        assertEquals(5, graph.edges.count { it.relation == RelationType.RESOLVES_TO })
        assertEquals(1, graph.nodes.count { it.type == NodeType.UPSTREAM && it.metadata["resolution"] == "HOST_TARGET" })
        assertTrue(graph.nodes.single { it.type == NodeType.DOMAIN && it.label == "example.com" }.id == HostDockerGraphFactory.create(discovery, target).nodes.single { it.type == NodeType.DOMAIN && it.label == "example.com" }.id)
        val caddyContainer = graph.nodes.single { it.type == NodeType.DOCKER_CONTAINER && it.label == "caddy" }
        assertTrue(graph.edges.any { it.source == caddyContainer.id && it.relation == RelationType.RUNS && graph.nodes.single { node -> node.id == it.target }.type == NodeType.REVERSE_PROXY })
    }

    @Test fun `adds canonical host services listeners ownership and route correlations`() {
        val docker = DockerHelperParser.parse(checkNotNull(javaClass.getResource("/docker/helper-systemd-realistic.json")).readText())
        val discovery = HostDiscoveryResult(HostInfo("vps"), docker)
        val target = SshTarget("vps.example", 22, "vpsgraph", "/keys/vps")
        val graph = HostDockerGraphFactory.create(discovery, target)

        assertEquals(5, graph.nodes.count { it.type == NodeType.SYSTEMD_SERVICE })
        assertEquals(10, graph.nodes.count { it.type == NodeType.HOST_LISTENER })
        assertEquals(graph, HostDockerGraphFactory.create(discovery, target))
        assertEquals(6, graph.edges.count { it.relation == RelationType.LISTENS_ON })
        assertEquals(6, graph.edges.count { it.relation == RelationType.OWNED_BY })
        assertEquals(4, graph.edges.count { it.relation == RelationType.PUBLISHED_AS })
        assertEquals("systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service", graph.nodes.single { it.type == NodeType.SYSTEMD_SERVICE && it.label.startsWith("systemd-fsck") }.metadata["unit"])
        assertTrue(graph.nodes.any { it.type == NodeType.HOST_LISTENER && it.metadata["local address"] == "fe80::eac9:da0a:d32:2190%eth0" })

        val upstream = graph.nodes.single { it.type == NodeType.UPSTREAM && it.label == "host.docker.internal:19999" }
        assertEquals("HOST_SERVICE", upstream.metadata["host resolution"])
        assertEquals("netdata.service", upstream.metadata["host service"])
        val targeted = graph.edges.filter { it.source == upstream.id && it.relation == RelationType.TARGETS }.map { it.target }.toSet()
        assertEquals(1, targeted.size)
        assertTrue(graph.nodes.single { it.id in targeted }.metadata["local address"] == "172.17.0.1")
        assertFalse(graph.nodes.any { it.type == NodeType.SYSTEMD_SERVICE && it.id.contains("process", ignoreCase = true) })
        assertTrue(graph.edges.any { it.relation == RelationType.RESOLVES_TO })
    }

    @Test fun `published listener correlation requires exact Docker binding and ignores process names`() {
        val listeners = listOf(
            HostListenerInfo("tcp", "0.0.0.0", 8080, true, false, ListenerOwnershipState.UNRESOLVED),
            HostListenerInfo("tcp", "0.0.0.0", 9090, true, false, ListenerOwnershipState.UNRESOLVED, processName = "docker-proxy"),
            HostListenerInfo("udp", "0.0.0.0", 8080, true, false, ListenerOwnershipState.UNRESOLVED, processName = "docker-proxy"),
            HostListenerInfo("tcp", "127.0.0.1", 8080, false, true, ListenerOwnershipState.UNRESOLVED),
        )
        val container = DockerContainerInfo(
            id = "a".repeat(64),
            name = "web",
            ports = listOf(DockerPortInfo("3000", "tcp", "0.0.0.0", "8080")),
        )
        val graph = graphFor(container, listeners)

        val edge = graph.edges.single { it.relation == RelationType.PUBLISHED_AS }
        assertEquals("0.0.0.0", graph.nodes.single { it.id == edge.target }.metadata["local address"])
        assertEquals("tcp", graph.nodes.single { it.id == edge.target }.metadata["protocol"])
        assertFalse(graph.edges.any { it.relation == RelationType.OWNED_BY })
    }

    @Test fun `ambiguous duplicate Docker bindings omit published listener correlation`() {
        val binding = DockerPortInfo("3000", "tcp", "0.0.0.0", "8080")
        val first = DockerContainerInfo("a".repeat(64), "web-a", ports = listOf(binding))
        val second = DockerContainerInfo("b".repeat(64), "web-b", ports = listOf(binding))
        val listener = HostListenerInfo("tcp", "0.0.0.0", 8080, true, false, ListenerOwnershipState.UNRESOLVED)
        val graph = graphFor(listOf(first, second), listOf(listener))

        assertEquals(2, graph.nodes.count { it.type == NodeType.PORT })
        assertFalse(graph.edges.any { it.relation == RelationType.PUBLISHED_AS })
    }

    @Test fun `listener identity ignores mutable process and ownership metadata`() {
        val original = HostListenerInfo("tcp", "0.0.0.0", 8080, true, false, ListenerOwnershipState.UNRESOLVED, processName = "docker-proxy")
        val changed = original.copy(ownershipState = ListenerOwnershipState.UNKNOWN, processName = "different")
        val originalId = graphFor(emptyList(), listOf(original)).nodes.single { it.type == NodeType.HOST_LISTENER }.id
        val changedId = graphFor(emptyList(), listOf(changed)).nodes.single { it.type == NodeType.HOST_LISTENER }.id
        assertEquals(originalId, changedId)
    }

    @Test fun `release scale graph stays deterministic and within bridge and snapshot bounds`() {
        val containers = (0 until 300).map { containerIndex ->
            val resourceCount = if (containerIndex < 100) 4 else 3
            DockerContainerInfo(
                id = containerIndex.toString(16).padStart(64, '0'),
                name = "service-$containerIndex",
                image = "example/service:$containerIndex",
                state = if (containerIndex % 5 == 0) "stopped" else "running",
                compose = DockerContainerComposeInfo(project = "project-${containerIndex % 25}", service = "service-$containerIndex"),
                ports = (0 until resourceCount).map { portIndex -> DockerPortInfo((3000 + portIndex).toString(), if (portIndex % 2 == 0) "tcp" else "udp", "0.0.0.0", (20_000 + containerIndex * 4 + portIndex).toString()) },
                mounts = (0 until resourceCount).map { mountIndex -> DockerMountInfo("bind", "/srv/$containerIndex/$mountIndex", "/data/$mountIndex") },
                networks = listOf("network-${containerIndex % 100}"),
            )
        }
        val docker = DockerDiscoveryResult(DockerDiscoveryState.AVAILABLE, containers = containers)
        val target = SshTarget("scale.example", 22, "vpsgraph", "/keys/vps")
        val discovery = HostDiscoveryResult(HostInfo("scale-vps"), docker)

        val graph = HostDockerGraphFactory.create(discovery, target)
        assertEquals(graph, HostDockerGraphFactory.create(discovery, target))
        assertEquals(300, graph.nodes.count { it.type == NodeType.DOCKER_CONTAINER })
        assertEquals(1_000, graph.nodes.count { it.type == NodeType.PORT })
        assertEquals(1_000, graph.nodes.count { it.type == NodeType.MOUNT })
        assertEquals(100, graph.nodes.count { it.type == NodeType.NETWORK })
        assertTrue(Json.encodeToString(graph).length < 8 * 1024 * 1024)
    }

    private fun graphFor(container: DockerContainerInfo, listeners: List<HostListenerInfo>) = graphFor(listOf(container), listeners)

    private fun graphFor(containers: List<DockerContainerInfo>, listeners: List<HostListenerInfo>): InfraGraph {
        val docker = DockerDiscoveryResult(
            DockerDiscoveryState.AVAILABLE,
            containers = containers,
            listeners = ListenerDiscoveryInfo(OptionalDiscoveryState.DISCOVERED, items = listeners),
        )
        return HostDockerGraphFactory.create(HostDiscoveryResult(HostInfo("vps"), docker), SshTarget("vps.example", 22, "vpsgraph", "/keys/vps"))
    }
}
