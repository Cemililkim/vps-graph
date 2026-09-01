package com.ilkimgul.vpsgraph.core

private val DIAL_HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,251}[A-Za-z0-9])?")
private val IPV6_LITERAL = Regex("[0-9A-Fa-f:.]+")

enum class CaddyDialKind { DOCKER_NAME, HOST_TARGET, LOOPBACK, IP_ADDRESS }

data class CaddyDial(val host: String, val port: Int, val kind: CaddyDialKind)

object CaddyDialParser {
    fun parse(value: String?): CaddyDial? {
        val dial = value?.takeIf { it.isNotBlank() && it.length <= 255 && it.none(Char::isWhitespace) && it.none { character -> character.code < 32 || character in "{}$/\\@" } } ?: return null
        val host: String
        val portText: String
        if (dial.startsWith("[")) {
            val closing = dial.indexOf(']')
            if (closing <= 1 || closing + 2 >= dial.length || dial[closing + 1] != ':') return null
            host = dial.substring(1, closing).lowercase()
            portText = dial.substring(closing + 2)
            if (':' !in host || !IPV6_LITERAL.matches(host)) return null
        } else {
            val separator = dial.lastIndexOf(':')
            if (separator <= 0 || separator != dial.indexOf(':')) return null
            host = dial.substring(0, separator).lowercase()
            portText = dial.substring(separator + 1)
            if (!DIAL_HOST.matches(host)) return null
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val kind = when {
            host == "host.docker.internal" -> CaddyDialKind.HOST_TARGET
            host == "localhost" || host == "127.0.0.1" || host == "::1" -> CaddyDialKind.LOOPBACK
            isIpv4(host) || ':' in host -> CaddyDialKind.IP_ADDRESS
            else -> CaddyDialKind.DOCKER_NAME
        }
        val normalizedHost = if (':' in host) "[$host]" else host
        return CaddyDial(normalizedHost, port, kind)
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

enum class UpstreamResolutionState { RESOLVED, UNRESOLVED, AMBIGUOUS, HOST_TARGET, DYNAMIC }

data class UpstreamResolution(
    val state: UpstreamResolutionState,
    val dial: CaddyDial? = null,
    val container: DockerContainerInfo? = null,
    val matchingReason: String? = null,
    val sharedNetwork: String? = null,
)

object CaddyUpstreamResolver {
    fun resolve(instance: CaddyInstanceInfo, upstream: CaddyUpstreamInfo, docker: DockerDiscoveryResult): UpstreamResolution {
        if (upstream.sourceState != CaddyUpstreamSourceState.STATIC) return UpstreamResolution(UpstreamResolutionState.UNRESOLVED)
        val dial = CaddyDialParser.parse(upstream.dial) ?: return UpstreamResolution(UpstreamResolutionState.UNRESOLVED)
        if (dial.kind == CaddyDialKind.HOST_TARGET) return UpstreamResolution(UpstreamResolutionState.HOST_TARGET, dial, matchingReason = "Docker host target")
        if (dial.kind != CaddyDialKind.DOCKER_NAME) return UpstreamResolution(UpstreamResolutionState.UNRESOLVED, dial)
        val caddy = docker.containers.singleOrNull { it.id == instance.containerId }
            ?: return UpstreamResolution(UpstreamResolutionState.UNRESOLVED, dial)

        val exactNames = docker.containers.filter { it.name.equals(dial.host, ignoreCase = true) }
        if (exactNames.isNotEmpty()) return select(exactNames, caddy, dial, "Exact container name and TCP port")

        val services = docker.containers.filter { it.compose?.service?.equals(dial.host, ignoreCase = true) == true }
        if (services.isNotEmpty()) return select(services, caddy, dial, "Unique Compose service and TCP port")
        return UpstreamResolution(UpstreamResolutionState.UNRESOLVED, dial)
    }

    private fun select(candidates: List<DockerContainerInfo>, caddy: DockerContainerInfo, dial: CaddyDial, reason: String): UpstreamResolution {
        val matchingPort = candidates.filter { container -> container.ports.any { it.protocol.equals("tcp", ignoreCase = true) && it.containerPort.toIntOrNull() == dial.port } }
        if (matchingPort.isEmpty()) return UpstreamResolution(UpstreamResolutionState.UNRESOLVED, dial)
        val networkMatches = matchingPort.mapNotNull { container ->
            container.networks.intersect(caddy.networks.toSet()).sorted().firstOrNull()?.let { network -> container to network }
        }
        if (networkMatches.isEmpty()) return UpstreamResolution(UpstreamResolutionState.UNRESOLVED, dial)
        if (networkMatches.size > 1) return UpstreamResolution(UpstreamResolutionState.AMBIGUOUS, dial)
        val (container, network) = networkMatches.single()
        return UpstreamResolution(UpstreamResolutionState.RESOLVED, dial, container, "$reason on shared network $network", network)
    }
}
