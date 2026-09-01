package com.ilkimgul.vpsgraph.core

enum class HostResolutionState { HOST_TARGET, HOST_LISTENER, HOST_SERVICE, HOST_AMBIGUOUS, HOST_PARTIAL }

data class HostTargetResolution(
    val state: HostResolutionState,
    val listeners: List<HostListenerInfo> = emptyList(),
    val services: List<SystemdServiceInfo> = emptyList(),
) {
    val service: SystemdServiceInfo? get() = services.singleOrNull()
}

object HostTargetResolver {
    fun resolve(
        dial: CaddyDial,
        systemd: SystemdDiscoveryInfo,
        discovery: ListenerDiscoveryInfo,
    ): HostTargetResolution {
        if (dial.kind != CaddyDialKind.HOST_TARGET) return HostTargetResolution(HostResolutionState.HOST_TARGET)
        val eligible = discovery.items
            .filter { it.protocol == "tcp" && it.port == dial.port && !it.loopback }
            .sortedWith(compareBy(HostListenerInfo::protocol, HostListenerInfo::localAddress, HostListenerInfo::port))
        if (discovery.state == OptionalDiscoveryState.UNKNOWN && discovery.items.isEmpty()) return HostTargetResolution(HostResolutionState.HOST_TARGET)
        if (discovery.state != OptionalDiscoveryState.DISCOVERED) return HostTargetResolution(HostResolutionState.HOST_PARTIAL, eligible)
        if (eligible.isEmpty()) return HostTargetResolution(HostResolutionState.HOST_TARGET)

        val byUnit = systemd.services.associateBy(SystemdServiceInfo::unit)
        val resolved = eligible.mapNotNull { listener ->
            listener.systemdUnit
                ?.takeIf { listener.ownershipState == ListenerOwnershipState.SYSTEMD_SERVICE }
                ?.let(byUnit::get)
        }.distinctBy(SystemdServiceInfo::unit).sortedBy(SystemdServiceInfo::unit)
        val unresolved = eligible.count { listener ->
            listener.ownershipState != ListenerOwnershipState.SYSTEMD_SERVICE || listener.systemdUnit !in byUnit
        }
        return when {
            resolved.isEmpty() -> HostTargetResolution(HostResolutionState.HOST_LISTENER, eligible)
            unresolved > 0 || resolved.size > 1 -> HostTargetResolution(HostResolutionState.HOST_AMBIGUOUS, eligible, resolved)
            else -> HostTargetResolution(HostResolutionState.HOST_SERVICE, eligible, resolved)
        }
    }
}
