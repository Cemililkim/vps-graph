package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfraEdge
import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.InfraNode
import com.ilkimgul.vpsgraph.core.NodeType
import com.ilkimgul.vpsgraph.core.RelationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MockInfraGraph {
    val value = InfraGraph(
        nodes = listOf(
            InfraNode(
                id = "server:production-vps",
                label = "Production VPS",
                type = NodeType.SERVER,
                metadata = mapOf("hostname" to "production-vps", "os" to "Debian 13"),
            ),
            InfraNode(
                id = "proxy:caddy",
                label = "Caddy",
                type = NodeType.REVERSE_PROXY,
                metadata = mapOf("role" to "reverse proxy", "status" to "running"),
            ),
            InfraNode(
                id = "domain:portfolio",
                label = "portfolio.example.com",
                type = NodeType.DOMAIN,
                metadata = mapOf("protocol" to "HTTPS", "route" to ":3000"),
            ),
            InfraNode(
                id = "docker:engine",
                label = "Docker",
                type = NodeType.DOCKER_ENGINE,
                metadata = mapOf("role" to "container runtime"),
            ),
            InfraNode(
                id = "container:portfolio",
                label = "portfolio-container",
                type = NodeType.DOCKER_CONTAINER,
                metadata = mapOf("image" to "portfolio:latest", "status" to "running"),
            ),
            InfraNode(
                id = "port:3000",
                label = ":3000",
                type = NodeType.PORT,
                metadata = mapOf("protocol" to "TCP", "exposure" to "internal"),
            ),
            InfraNode(
                id = "directory:portfolio",
                label = "/srv/example-app",
                type = NodeType.DIRECTORY,
                metadata = mapOf("owner" to "vpsgraph", "purpose" to "application directory"),
            ),
        ),
        edges = listOf(
            InfraEdge("server-contains-caddy", "server:production-vps", "proxy:caddy", RelationType.CONTAINS),
            InfraEdge("caddy-routes-domain", "proxy:caddy", "domain:portfolio", RelationType.ROUTES_TO),
            InfraEdge("domain-routes-container", "domain:portfolio", "container:portfolio", RelationType.ROUTES_TO),
            InfraEdge("server-contains-docker", "server:production-vps", "docker:engine", RelationType.CONTAINS),
            InfraEdge("docker-runs-container", "docker:engine", "container:portfolio", RelationType.RUNS),
            InfraEdge("container-exposes-port", "container:portfolio", "port:3000", RelationType.EXPOSES),
            InfraEdge("container-located-in-directory", "container:portfolio", "directory:portfolio", RelationType.LOCATED_IN),
        ),
    )

    fun asJson(): String = Json.encodeToString(value)
}
