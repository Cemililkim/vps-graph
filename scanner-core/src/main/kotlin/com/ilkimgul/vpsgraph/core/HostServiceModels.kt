package com.ilkimgul.vpsgraph.core

enum class OptionalDiscoveryState {
    DISCOVERED,
    PARTIAL,
    NOT_AVAILABLE,
    NOT_SYSTEMD,
    COMMAND_FAILED,
    MALFORMED,
    UNKNOWN,
}

enum class DiscoveryStatusReason {
    COMMAND_FAILED,
    TRUNCATED,
    LIST_ROW_MALFORMED,
    SHOW_RECORD_MISSING,
    SHOW_RECORD_MALFORMED,
    LISTENER_ROW_MALFORMED,
    LISTENER_PROTOCOL_UNSUPPORTED,
    LISTENER_ADDRESS_UNSUPPORTED,
    LISTENER_PORT_INVALID,
    UNKNOWN,
}

data class SystemdDiscoveryInfo(
    val state: OptionalDiscoveryState = OptionalDiscoveryState.UNKNOWN,
    val statusReasons: List<DiscoveryStatusReason> = emptyList(),
    val services: List<SystemdServiceInfo> = emptyList(),
)

data class SystemdServiceInfo(
    val unit: String,
    val description: String? = null,
    val loadState: String? = null,
    val activeState: String? = null,
    val subState: String? = null,
    val unitFileState: String? = null,
    val serviceType: String? = null,
    val user: String? = null,
    val group: String? = null,
    val controlGroup: String? = null,
)

enum class ListenerOwnershipState { SYSTEMD_SERVICE, UNRESOLVED, AMBIGUOUS, UNKNOWN }

data class ListenerDiscoveryInfo(
    val state: OptionalDiscoveryState = OptionalDiscoveryState.UNKNOWN,
    val statusReasons: List<DiscoveryStatusReason> = emptyList(),
    val items: List<HostListenerInfo> = emptyList(),
)

data class HostListenerInfo(
    val protocol: String,
    val localAddress: String,
    val port: Int,
    val wildcard: Boolean,
    val loopback: Boolean,
    val ownershipState: ListenerOwnershipState,
    val systemdUnit: String? = null,
    val processName: String? = null,
) {
    val canonicalKey: String get() = "$protocol|$localAddress|$port"
}
