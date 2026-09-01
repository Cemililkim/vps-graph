package com.ilkimgul.vpsgraph.core

data class DockerEngineInfo(val version: String?)

data class DockerComposeProjectInfo(
    val project: String,
    val workingDirectory: String? = null,
    val configFiles: String? = null,
)

data class DockerContainerComposeInfo(
    val project: String? = null,
    val service: String? = null,
    val workingDirectory: String? = null,
    val configFiles: String? = null,
)

data class DockerPortInfo(
    val containerPort: String,
    val protocol: String,
    val hostIp: String? = null,
    val hostPort: String? = null,
)

data class DockerMountInfo(
    val type: String,
    val source: String? = null,
    val destination: String,
    val name: String? = null,
    val readOnly: Boolean = false,
)

data class DockerContainerInfo(
    val id: String,
    val name: String,
    val image: String? = null,
    val state: String? = null,
    val restartPolicy: String? = null,
    val compose: DockerContainerComposeInfo? = null,
    val ports: List<DockerPortInfo> = emptyList(),
    val mounts: List<DockerMountInfo> = emptyList(),
    val networks: List<String> = emptyList(),
)

enum class DockerDiscoveryState {
    AVAILABLE,
    HELPER_NOT_INSTALLED,
    HELPER_NOT_AUTHORIZED,
    DOCKER_UNAVAILABLE,
    SCAN_FAILED,
}

data class DockerDiscoveryResult(
    val state: DockerDiscoveryState,
    val engine: DockerEngineInfo? = null,
    val composeProjects: List<DockerComposeProjectInfo> = emptyList(),
    val containers: List<DockerContainerInfo> = emptyList(),
    val caddy: CaddyDiscoveryResult = CaddyDiscoveryResult(),
    val systemd: SystemdDiscoveryInfo = SystemdDiscoveryInfo(),
    val listeners: ListenerDiscoveryInfo = ListenerDiscoveryInfo(),
) {
    val statusLabel: String
        get() = when (state) {
            DockerDiscoveryState.AVAILABLE -> "Docker discovered"
            DockerDiscoveryState.HELPER_NOT_INSTALLED -> "Docker helper not installed"
            DockerDiscoveryState.HELPER_NOT_AUTHORIZED -> "Docker access not configured"
            DockerDiscoveryState.DOCKER_UNAVAILABLE -> "Docker unavailable"
            DockerDiscoveryState.SCAN_FAILED -> "Docker scan partial"
        }
}

data class HostDiscoveryResult(val host: HostInfo, val docker: DockerDiscoveryResult)
