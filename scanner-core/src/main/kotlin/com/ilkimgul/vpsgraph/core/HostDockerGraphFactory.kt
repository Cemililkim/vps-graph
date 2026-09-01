package com.ilkimgul.vpsgraph.core

import java.security.MessageDigest

object HostDockerGraphFactory {
    fun create(discovery: HostDiscoveryResult, target: SshTarget): InfraGraph {
        val host = discovery.host
        val serverId = "server:${identity("${target.host}:${target.port}:${target.username}:${host.hostname}")}" 
        val engineId = "$serverId:docker"
        val nodes = mutableListOf(serverNode(serverId, host, target, discovery.docker), engineNode(engineId, discovery.docker))
        val edges = mutableListOf(InfraEdge("$serverId:runs:$engineId", serverId, engineId, RelationType.RUNS))
        val hostResources = appendHostResources(serverId, discovery.docker.systemd, discovery.docker.listeners, nodes, edges)

        if (discovery.docker.state != DockerDiscoveryState.AVAILABLE) return InfraGraph(nodes, edges)

        val projects = (discovery.docker.composeProjects + discovery.docker.containers.mapNotNull { container ->
            container.compose?.project?.let { DockerComposeProjectInfo(it, container.compose.workingDirectory, container.compose.configFiles) }
        }).associateBy { it.project }.toSortedMap()
        val projectIds = projects.mapValues { (project, info) ->
            val id = "$engineId:compose:${identity(project)}"
            val count = discovery.docker.containers.count { it.compose?.project == project }
            nodes += InfraNode(
                id,
                project,
                NodeType.DOCKER_COMPOSE_PROJECT,
                linkedMapOf<String, String>().apply {
                    put("project", project)
                    info.workingDirectory?.let { put("working directory", it) }
                    info.configFiles?.let { put("config files", it) }
                    put("container count", count.toString())
                },
            )
            edges += InfraEdge("$engineId:contains:$id", engineId, id, RelationType.CONTAINS)
            id
        }

        val networkContainers = sortedMapOf<String, MutableList<String>>()
        val containerIds = mutableMapOf<String, String>()
        val publishedPorts = mutableListOf<PublishedPort>()
        discovery.docker.containers.sortedBy { it.id }.forEach { container ->
            val containerId = "$engineId:container:${identity(container.id)}"
            containerIds[container.id] = containerId
            nodes += containerNode(containerId, container)
            val parent = container.compose?.project?.let(projectIds::get) ?: engineId
            edges += InfraEdge(
                "$parent:${if (parent == engineId) "runs" else "contains"}:$containerId",
                parent,
                containerId,
                if (parent == engineId) RelationType.RUNS else RelationType.CONTAINS,
            )
            container.ports.forEachIndexed { index, port ->
                val portId = "$containerId:port:${identity("$index:${port.containerPort}/${port.protocol}:${port.hostIp}:${port.hostPort}")}" 
                nodes += portNode(portId, port)
                edges += InfraEdge("$containerId:exposes:$portId", containerId, portId, RelationType.EXPOSES)
                if (port.hostPort != null) publishedPorts += PublishedPort(portId, port)
            }
            container.mounts.forEachIndexed { index, mount ->
                val mountId = "$containerId:mount:${identity("$index:${mount.type}:${mount.source}:${mount.name}:${mount.destination}")}" 
                nodes += mountNode(mountId, mount)
                edges += InfraEdge("$containerId:mounts:$mountId", containerId, mountId, RelationType.MOUNTS)
            }
            container.networks.forEach { network -> networkContainers.getOrPut(network) { mutableListOf() }.add(container.name) }
        }
        networkContainers.forEach { (network, containers) ->
            val networkId = "$engineId:network:${identity(network)}"
            nodes += InfraNode(networkId, network, NodeType.NETWORK, mapOf("network" to network, "connected containers" to containers.sorted().joinToString(", ")))
            discovery.docker.containers.filter { network in it.networks }.forEach { container ->
                val containerId = "$engineId:container:${identity(container.id)}"
                edges += InfraEdge("$containerId:network:$networkId", containerId, networkId, RelationType.CONNECTED_TO)
            }
        }
        appendPublishedListenerCorrelations(publishedPorts, hostResources, edges)
        appendCaddyRouting(serverId, discovery.docker, containerIds, hostResources, nodes, edges)
        return InfraGraph(nodes, edges)
    }

    private fun serverNode(id: String, host: HostInfo, target: SshTarget, discovery: DockerDiscoveryResult): InfraNode = InfraNode(
        id,
        host.hostname,
        NodeType.SERVER,
        linkedMapOf<String, String>().apply {
            put("hostname", host.hostname)
            put("ssh host", target.host)
            put("ssh username", target.username)
            host.osName?.let { put("operating system", it) }
            host.osVersion?.let { put("os version", it) }
            host.kernelName?.let { put("kernel", listOfNotNull(it, host.kernelVersion).joinToString(" ")) }
            host.architecture?.let { put("architecture", it) }
            put("systemd discovery status", discovery.systemd.state.name)
            put("listener discovery status", discovery.listeners.state.name)
            if (discovery.systemd.statusReasons.isNotEmpty()) put("systemd status reasons", discovery.systemd.statusReasons.joinToString(",") { it.name })
            if (discovery.listeners.statusReasons.isNotEmpty()) put("listener status reasons", discovery.listeners.statusReasons.joinToString(",") { it.name })
        },
    )

    private fun engineNode(id: String, docker: DockerDiscoveryResult): InfraNode = InfraNode(
        id,
        if (docker.state == DockerDiscoveryState.AVAILABLE) "Docker" else "Docker unavailable",
        NodeType.DOCKER_ENGINE,
        linkedMapOf<String, String>().apply {
            put("discovery status", docker.statusLabel)
            put("docker discovery state", docker.state.name)
            put("caddy discovery status", docker.caddy.statusLabel)
            put("caddy discovery state", docker.caddy.state.name)
            docker.engine?.version?.let { put("docker version", it) }
        },
    )

    private fun appendHostResources(
        serverId: String,
        systemd: SystemdDiscoveryInfo,
        listeners: ListenerDiscoveryInfo,
        nodes: MutableList<InfraNode>,
        edges: MutableList<InfraEdge>,
    ): HostResources {
        val listenerCounts = listeners.items
            .filter { it.ownershipState == ListenerOwnershipState.SYSTEMD_SERVICE && it.systemdUnit != null }
            .groupingBy { it.systemdUnit!! }.eachCount()
        val serviceIds = systemd.services.associate { service ->
            val id = "$serverId:systemd:${identity(service.unit)}"
            nodes += InfraNode(id, service.unit, NodeType.SYSTEMD_SERVICE, linkedMapOf<String, String>().apply {
                put("unit", service.unit)
                service.description?.let { put("description", it) }
                service.loadState?.let { put("load state", it) }
                service.activeState?.let { put("active state", it) }
                service.subState?.let { put("sub state", it) }
                service.unitFileState?.let { put("unit file state", it) }
                service.serviceType?.let { put("service type", it) }
                service.user?.let { put("user", it) }
                service.group?.let { put("group", it) }
                service.controlGroup?.let { put("control group", it) }
                put("listener count", (listenerCounts[service.unit] ?: 0).toString())
                put("discovery status", systemd.state.name)
            })
            edges += InfraEdge("$serverId:runs:$id", serverId, id, RelationType.RUNS)
            service.unit to id
        }
        val listenerIds = listeners.items.associate { listener ->
            val id = "$serverId:listener:${identity(listener.canonicalKey)}"
            nodes += InfraNode(id, listenerLabel(listener), NodeType.HOST_LISTENER, linkedMapOf<String, String>().apply {
                put("protocol", listener.protocol)
                put("local address", listener.localAddress)
                put("port", listener.port.toString())
                put("bind", listenerLabel(listener))
                put("wildcard", listener.wildcard.toString())
                put("loopback", listener.loopback.toString())
                put("ownership state", listener.ownershipState.name)
                listener.systemdUnit?.let { put("systemd unit", it) }
                listener.processName?.let { put("process name", it) }
                put("discovery status", listeners.state.name)
            })
            edges += InfraEdge("$serverId:contains:$id", serverId, id, RelationType.CONTAINS)
            listener.systemdUnit?.takeIf { listener.ownershipState == ListenerOwnershipState.SYSTEMD_SERVICE }?.let(serviceIds::get)?.let { serviceId ->
                edges += InfraEdge("$serviceId:listens:$id", serviceId, id, RelationType.LISTENS_ON)
                edges += InfraEdge("$id:owned-by:$serviceId", id, serviceId, RelationType.OWNED_BY)
            }
            listener.canonicalKey to id
        }
        return HostResources(systemd, listeners, serviceIds, listenerIds)
    }

    private fun appendPublishedListenerCorrelations(
        ports: List<PublishedPort>,
        resources: HostResources,
        edges: MutableList<InfraEdge>,
    ) {
        val portsByBinding = ports.mapNotNull { published -> published.port.bindingKey()?.let { it to published.nodeId } }
            .groupBy({ it.first }, { it.second })
        resources.listeners.items.forEach { listener ->
            val matches = portsByBinding[listener.bindingKey()].orEmpty()
            if (matches.size == 1) {
                val listenerId = resources.listenerIds.getValue(listener.canonicalKey)
                edges += InfraEdge("${matches.single()}:published-as:$listenerId", matches.single(), listenerId, RelationType.PUBLISHED_AS)
            }
        }
    }

    private fun appendCaddyRouting(
        serverId: String,
        docker: DockerDiscoveryResult,
        containerIds: Map<String, String>,
        hostResources: HostResources,
        nodes: MutableList<InfraNode>,
        edges: MutableList<InfraEdge>,
    ) {
        val domains = sortedMapOf<String, DomainSummary>()
        docker.caddy.instances.sortedWith(compareBy(CaddyInstanceInfo::containerName, CaddyInstanceInfo::containerId)).forEach { instance ->
            val proxyId = "$serverId:caddy:${identity(instance.containerId)}"
            val routeCount = instance.routes.size
            val domainCount = instance.routes.flatMap(CaddyRouteInfo::hosts).mapNotNull(::normalizeDomain).distinct().size
            nodes += InfraNode(proxyId, instance.containerName, NodeType.REVERSE_PROXY, linkedMapOf<String, String>().apply {
                put("proxy", "Caddy")
                put("container name", instance.containerName)
                put("discovery status", instance.state.name)
                put("route count", routeCount.toString())
                put("domain count", domainCount.toString())
                instance.image?.let { put("image", it) }
            })
            containerIds[instance.containerId]?.let { containerId ->
                edges += InfraEdge("$containerId:runs:$proxyId", containerId, proxyId, RelationType.RUNS)
            }

            instance.routes.sortedBy(CaddyRouteInfo::canonicalKey).forEach { route ->
                val routeId = identity("${instance.containerId}|${route.canonicalKey}")
                val routeHosts = route.hosts.mapNotNull(::normalizeDomain).distinct().sorted()
                val routePaths = route.paths.distinct().sorted()
                val upstreams = buildList {
                    addAll(route.upstreams)
                    if (route.dynamicUpstreams) add(CaddyUpstreamInfo("dynamic", CaddyUpstreamSourceState.UNRESOLVED))
                    if (isEmpty()) add(CaddyUpstreamInfo())
                }
                upstreams.forEachIndexed { index, upstream ->
                    val resolution = if (route.dynamicUpstreams && upstream.dial == "dynamic") {
                        UpstreamResolution(UpstreamResolutionState.DYNAMIC)
                    } else CaddyUpstreamResolver.resolve(instance, upstream, docker)
                    val hostResolution = resolution.dial
                        ?.takeIf { resolution.state == UpstreamResolutionState.HOST_TARGET }
                        ?.let { HostTargetResolver.resolve(it, hostResources.systemd, hostResources.listeners) }
                    val dialLabel = when {
                        resolution.state == UpstreamResolutionState.DYNAMIC -> "Dynamic upstream"
                        upstream.dial != null -> upstream.dial
                        else -> "Unresolved upstream"
                    }
                    val upstreamId = "$proxyId:route:$routeId:upstream:${identity("$index:${upstream.canonicalKey}")}"
                    val metadata = linkedMapOf<String, String>().apply {
                        put("dial", dialLabel)
                        put("resolution", resolution.state.name)
                        hostResolution?.let { host ->
                            put("host resolution", host.state.name)
                            put("target kind", host.state.name.removePrefix("HOST_"))
                            put("matching port", "${resolution.dial?.port}/tcp")
                            put("host resolution reason", hostResolutionReason(host.state))
                            host.service?.let { put("host service", it.unit) }
                            if (host.listeners.isNotEmpty()) put("eligible listeners", host.listeners.joinToString(", ", transform = ::listenerLabel))
                            if (host.services.size > 1) put("candidate services", host.services.joinToString(", ") { it.unit })
                        }
                        put("route id", routeId)
                        put("backend set size", upstreams.size.toString())
                        if (routeHosts.isNotEmpty()) put("domains", routeHosts.joinToString(", "))
                        if (routePaths.isNotEmpty()) put("paths", routePaths.joinToString(", "))
                        resolution.matchingReason?.let { put("matching reason", it) }
                        resolution.sharedNetwork?.let { put("shared network", it) }
                        resolution.container?.let { container ->
                            put("container", container.name)
                            container.compose?.project?.let { put("compose project", it) }
                            container.compose?.service?.let { put("compose service", it) }
                            container.state?.let { put("container state", it) }
                        }
                    }
                    nodes += InfraNode(upstreamId, dialLabel, NodeType.UPSTREAM, metadata)
                    edges += InfraEdge("$proxyId:proxies:$upstreamId", proxyId, upstreamId, RelationType.PROXIES_TO)
                    resolution.container?.let { container ->
                        containerIds[container.id]?.let { targetId ->
                            edges += InfraEdge("$upstreamId:resolves:$targetId", upstreamId, targetId, RelationType.RESOLVES_TO)
                        }
                    }
                    hostResolution?.listeners?.forEach { listener ->
                        hostResources.listenerIds[listener.canonicalKey]?.let { listenerId ->
                            edges += InfraEdge("$upstreamId:targets:$listenerId", upstreamId, listenerId, RelationType.TARGETS)
                        }
                    }
                    routeHosts.forEach { host ->
                        val domainId = "$serverId:domain:${identity(host)}"
                        val summary = domains.getOrPut(host) { DomainSummary(domainId, host) }
                        summary.routeIds += routeId
                        summary.paths += routePaths
                        summary.upstreams += dialLabel
                        summary.resolutions += hostResolution?.state?.name ?: resolution.state.name
                        summary.proxies += instance.containerName
                        resolution.container?.name?.let(summary.targets::add)
                        hostResolution?.service?.unit?.let(summary.hostServices::add)
                        if (index == 0) edges += InfraEdge("$domainId:route:$routeId:proxy:$proxyId", domainId, proxyId, RelationType.ROUTES_THROUGH)
                        edges += InfraEdge("$domainId:route:$routeId:upstream:$upstreamId", domainId, upstreamId, RelationType.ROUTES_TO)
                    }
                }
            }
        }
        domains.values.forEach { domain ->
            nodes += InfraNode(domain.id, domain.host, NodeType.DOMAIN, linkedMapOf<String, String>().apply {
                put("domain", domain.host)
                put("route count", domain.routeIds.size.toString())
                if (domain.paths.isNotEmpty()) put("paths", domain.paths.sorted().joinToString(", "))
                put("resolution", summarizedResolution(domain.resolutions))
                if (domain.upstreams.isNotEmpty()) put("upstreams", domain.upstreams.sorted().joinToString(", "))
                if (domain.proxies.isNotEmpty()) put("caddy instance", domain.proxies.sorted().joinToString(", "))
                if (domain.targets.isNotEmpty()) put("resolved containers", domain.targets.sorted().joinToString(", "))
                if (domain.hostServices.isNotEmpty()) put("resolved host services", domain.hostServices.sorted().joinToString(", "))
            })
        }
    }

    private fun hostResolutionReason(state: HostResolutionState): String = when (state) {
        HostResolutionState.HOST_SERVICE -> "Non-loopback TCP listeners share one Systemd owner."
        HostResolutionState.HOST_LISTENER -> "Eligible host listeners lack deterministic Systemd ownership."
        HostResolutionState.HOST_AMBIGUOUS -> "Eligible host listeners do not identify one deterministic Systemd owner."
        HostResolutionState.HOST_PARTIAL -> "Listener discovery is incomplete."
        HostResolutionState.HOST_TARGET -> "No eligible non-loopback TCP listener was discovered."
    }

    private fun summarizedResolution(values: Set<String>): String = when {
        values.size == 1 -> values.single()
        UpstreamResolutionState.AMBIGUOUS.name in values -> UpstreamResolutionState.AMBIGUOUS.name
        UpstreamResolutionState.UNRESOLVED.name in values -> UpstreamResolutionState.UNRESOLVED.name
        values.isEmpty() -> UpstreamResolutionState.UNRESOLVED.name
        else -> "MIXED"
    }

    private data class DomainSummary(
        val id: String,
        val host: String,
        val routeIds: MutableSet<String> = sortedSetOf(),
        val paths: MutableSet<String> = sortedSetOf(),
        val upstreams: MutableSet<String> = sortedSetOf(),
        val resolutions: MutableSet<String> = sortedSetOf(),
        val proxies: MutableSet<String> = sortedSetOf(),
        val targets: MutableSet<String> = sortedSetOf(),
        val hostServices: MutableSet<String> = sortedSetOf(),
    )

    private fun containerNode(id: String, container: DockerContainerInfo): InfraNode = InfraNode(
        id,
        container.name,
        NodeType.DOCKER_CONTAINER,
        linkedMapOf<String, String>().apply {
            put("container id", container.id.take(12))
            container.image?.let { put("image", it) }
            container.state?.let { put("state", it) }
            container.restartPolicy?.let { put("restart policy", it) }
            container.compose?.project?.let { put("compose project", it) }
            container.compose?.service?.let { put("compose service", it) }
            if (container.ports.isNotEmpty()) put("ports", container.ports.joinToString(", ") { portLabel(it) })
            if (container.networks.isNotEmpty()) put("networks", container.networks.joinToString(", "))
            if (container.mounts.isNotEmpty()) put("mounts", container.mounts.joinToString(", ") { mountLabel(it) })
        },
    )

    private fun portNode(id: String, port: DockerPortInfo): InfraNode = InfraNode(
        id,
        portLabel(port),
        NodeType.PORT,
        linkedMapOf<String, String>().apply {
            put("container port", port.containerPort)
            put("protocol", port.protocol)
            if (port.hostPort == null) put("exposure", "internal only") else {
                put("host binding", listOfNotNull(port.hostIp, port.hostPort).joinToString(":"))
                put("exposure", "published")
            }
        },
    )

    private fun mountNode(id: String, mount: DockerMountInfo): InfraNode = InfraNode(
        id,
        mountLabel(mount),
        NodeType.MOUNT,
        linkedMapOf<String, String>().apply {
            put("type", mount.type)
            mount.source?.let { put("source", it) }
            mount.name?.let { put("volume name", it) }
            put("destination", mount.destination)
            put("access", if (mount.readOnly) "read-only" else "read-write")
        },
    )

    private fun portLabel(port: DockerPortInfo): String = buildString {
        append("${port.containerPort}/${port.protocol}")
        if (port.hostPort != null) append(" → ${port.hostIp ?: "host"}:${port.hostPort}") else append(" internal")
    }

    private fun mountLabel(mount: DockerMountInfo): String = "${mount.name ?: mount.source ?: mount.type} → ${mount.destination}"

    private fun listenerLabel(listener: HostListenerInfo): String {
        val address = if (':' in listener.localAddress) "[${listener.localAddress}]" else listener.localAddress
        return "$address:${listener.port}/${listener.protocol}"
    }

    private fun DockerPortInfo.bindingKey(): BindingKey? {
        val address = hostIp?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank) ?: return null
        val port = hostPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val normalizedProtocol = protocol.lowercase().takeIf { it == "tcp" || it == "udp" } ?: return null
        return BindingKey(normalizedProtocol, address.lowercase(), port)
    }

    private fun HostListenerInfo.bindingKey(): BindingKey = BindingKey(protocol.lowercase(), localAddress.lowercase(), port)

    private data class HostResources(
        val systemd: SystemdDiscoveryInfo,
        val listeners: ListenerDiscoveryInfo,
        val serviceIds: Map<String, String>,
        val listenerIds: Map<String, String>,
    )

    private data class PublishedPort(val nodeId: String, val port: DockerPortInfo)
    private data class BindingKey(val protocol: String, val address: String, val port: Int)

    private fun identity(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
}
