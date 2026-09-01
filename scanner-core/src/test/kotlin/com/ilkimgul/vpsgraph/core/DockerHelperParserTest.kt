package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DockerHelperParserTest {
    @Test fun `parses schema version one and ignores unknown sensitive fields`() {
        val result = DockerHelperParser.parse(fixture("helper-success"))

        assertEquals(DockerDiscoveryState.AVAILABLE, result.state)
        assertEquals("29.4.1", result.engine?.version)
        assertEquals(listOf("caddy", "portfolio"), result.composeProjects.map { it.project })
        assertEquals(listOf("portfolio-api", "portfolio-web", "worker"), result.containers.map { it.name })
        val api = result.containers.first()
        assertEquals(listOf("portfolio_default", "web"), api.networks)
        assertTrue(api.ports.any { it.containerPort == "4000" && it.hostPort == null })
        assertTrue(api.ports.any { it.containerPort == "443" && it.hostIp == "[::]" && it.hostPort == "443" })
        assertEquals("portfolio-data", api.mounts.single { it.type == "volume" }.name)
        assertTrue(api.mounts.single { it.type == "volume" }.readOnly)
        val serialized = result.toString()
        listOf("DATABASE_URL", "Password", "Token", "Secret", "Cmd", "Entrypoint", "Labels", "secret").forEach {
            assertFalse(serialized.contains(it))
        }
    }

    @Test fun `accepts zero containers`() {
        val result = DockerHelperParser.parse(fixture("helper-empty"))
        assertEquals(DockerDiscoveryState.AVAILABLE, result.state)
        assertTrue(result.containers.isEmpty())
    }

    @Test fun `maps helper error without propagating its message`() {
        val result = DockerHelperParser.parse(fixture("helper-error"))
        assertEquals(DockerDiscoveryState.DOCKER_UNAVAILABLE, result.state)
        assertFalse(result.statusLabel.contains("sensitive"))
    }

    @Test fun `rejects unsupported malformed and truncated schemas`() {
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerHelperParser.parse(fixture("helper-unsupported")).state)
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerHelperParser.parse("{bad json").state)
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerHelperParser.parse("{\"schemaVersion\":1").state)
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerHelperParser.parse("x".repeat(8 * 1024 * 1024 + 1)).state)
    }

    @Test fun `missing optional fields remain absent`() {
        val result = DockerHelperParser.parse("""{"schemaVersion":1,"ok":true,"engine":{},"containers":[{"id":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","name":"minimal"}]}""")
        val container = result.containers.single()
        assertEquals(null, container.image)
        assertTrue(container.ports.isEmpty())
        assertTrue(container.mounts.isEmpty())
    }

    @Test fun `parses optional sanitized Caddy discovery and normalizes domains`() {
        val result = DockerHelperParser.parse(fixture("helper-caddy-realistic"))
        assertEquals(CaddyDiscoveryState.DISCOVERED, result.caddy.state)
        val routes = result.caddy.instances.single().routes
        assertEquals(6, routes.size)
        assertTrue(routes.flatMap(CaddyRouteInfo::hosts).contains("example.com"))
        assertEquals(listOf("/api/*"), routes.single { "api.example.com" in it.hosts }.paths)
    }

    @Test fun `Caddy domains normalize case trailing dots and supported wildcards only`() {
        assertEquals("admin.example.com", normalizeDomain(" Admin.Example.COM. "))
        assertEquals("*.example.com", normalizeDomain("*.Example.COM."))
        assertEquals(null, normalizeDomain("{env.SECRET}.example.com"))
        assertEquals(null, normalizeDomain("bad..example.com"))
    }

    @Test fun `absent and malformed optional Caddy sections preserve Docker discovery`() {
        assertEquals(CaddyDiscoveryState.NOT_DETECTED, DockerHelperParser.parse(fixture("helper-success")).caddy.state)
        val malformed = DockerHelperParser.parse("""{"schemaVersion":1,"ok":true,"engine":{},"containers":[],"caddy":{"status":"DISCOVERED","instances":"bad"}}""")
        assertEquals(DockerDiscoveryState.AVAILABLE, malformed.state)
        assertEquals(CaddyDiscoveryState.MALFORMED, malformed.caddy.state)
        val partial = DockerHelperParser.parse("""{"schemaVersion":1,"ok":true,"engine":{},"containers":[],"caddy":{"status":"DISCOVERED","instances":[{"containerId":"id","containerName":"caddy","status":"DISCOVERED","routes":[{},"bad"]}]}}""")
        assertEquals(DockerDiscoveryState.AVAILABLE, partial.state)
        assertEquals(CaddyDiscoveryState.PARTIAL, partial.caddy.state)
    }

    @Test fun `parses optional Systemd services and host listeners without broadening the allowlist`() {
        val result = DockerHelperParser.parse(fixture("helper-systemd-realistic"))

        assertEquals(OptionalDiscoveryState.DISCOVERED, result.systemd.state)
        assertEquals(5, result.systemd.services.size)
        assertEquals(OptionalDiscoveryState.DISCOVERED, result.listeners.state)
        assertEquals(10, result.listeners.items.size)
        assertTrue(result.systemd.services.any { it.unit == "systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service" })
        assertTrue(result.listeners.items.any { it.localAddress == "fe80::eac9:da0a:d32:2190%eth0" && it.protocol == "udp" })
        assertFalse(result.toString().contains("unknownSecret"))
    }

    @Test fun `absent optional host sections remain unknown and malformed rows degrade only their section`() {
        val absent = DockerHelperParser.parse(fixture("helper-success"))
        assertEquals(OptionalDiscoveryState.UNKNOWN, absent.systemd.state)
        assertEquals(OptionalDiscoveryState.UNKNOWN, absent.listeners.state)

        val malformed = DockerHelperParser.parse("""{"schemaVersion":1,"ok":true,"engine":{},"containers":[],"systemd":{"status":"DISCOVERED","services":[{"unit":"ssh.service"},{}]},"listeners":{"status":"DISCOVERED","items":[{"protocol":"tcp","localAddress":"0.0.0.0","port":22,"wildcard":true,"loopback":false},{}]}}""")
        assertEquals(DockerDiscoveryState.AVAILABLE, malformed.state)
        assertEquals(OptionalDiscoveryState.PARTIAL, malformed.systemd.state)
        assertEquals(listOf("ssh.service"), malformed.systemd.services.map { it.unit })
        assertEquals(OptionalDiscoveryState.PARTIAL, malformed.listeners.state)
        assertEquals(1, malformed.listeners.items.size)
    }

    @Test fun `accepts fixed optional statuses and safely maps unknown reasons`() {
        listOf("DISCOVERED", "PARTIAL", "COMMAND_FAILED", "NOT_SYSTEMD").forEach { status ->
            val result = DockerHelperParser.parse("""{"schemaVersion":1,"ok":true,"engine":{},"containers":[],"systemd":{"status":"$status","statusReasons":["COMMAND_FAILED","FUTURE_REASON"],"services":[]},"listeners":{"status":"$status","items":[]}}""")
            assertEquals(OptionalDiscoveryState.valueOf(status), result.systemd.state)
            assertEquals(listOf(DiscoveryStatusReason.COMMAND_FAILED, DiscoveryStatusReason.UNKNOWN), result.systemd.statusReasons)
            assertEquals(OptionalDiscoveryState.valueOf(status), result.listeners.state)
        }
    }

    @Test fun `fixed Docker helper command cannot carry caller input`() {
        assertEquals("sudo -n /usr/local/libexec/vpsgraph-docker-scan", RemoteCommand.DOCKER_HELPER.wireValue)
        assertEquals(RemoteCommand.hostDiscoveryCommands + RemoteCommand.DOCKER_HELPER, RemoteCommand.hostDiscoveryCommands + RemoteCommand.DOCKER_HELPER)
    }

    @Test fun `nonzero helper result degrades without raw output`() {
        val result = DockerDiscovery.from(CommandResult(RemoteCommand.DOCKER_HELPER, "raw", "sudo: a password is required", 1))
        assertEquals(DockerDiscoveryState.HELPER_NOT_AUTHORIZED, result.state)
        assertFalse(result.statusLabel.contains("password"))
        assertEquals(DockerDiscoveryState.HELPER_NOT_AUTHORIZED, DockerDiscovery.from(CommandResult(RemoteCommand.DOCKER_HELPER, "", "permission denied", 126)).state)
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerDiscovery.from(CommandResult(RemoteCommand.DOCKER_HELPER, "", "", -1, timedOut = true)).state)
        assertEquals(DockerDiscoveryState.SCAN_FAILED, DockerDiscovery.from(CommandResult(RemoteCommand.DOCKER_HELPER, "", "", -1, outputLimitExceeded = true)).state)
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/docker/$name.json")).readText()
}
