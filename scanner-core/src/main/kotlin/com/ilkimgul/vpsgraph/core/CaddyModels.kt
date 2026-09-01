package com.ilkimgul.vpsgraph.core

enum class CaddyDiscoveryState {
    NOT_DETECTED,
    DISCOVERED,
    CONFIG_MOUNT_MISSING,
    CONFIG_UNAVAILABLE,
    CONFIG_TOO_LARGE,
    CONFIG_INVALID,
    PARTIAL,
    AMBIGUOUS,
    MALFORMED,
    UNKNOWN,
}

data class CaddyDiscoveryResult(
    val state: CaddyDiscoveryState = CaddyDiscoveryState.NOT_DETECTED,
    val instances: List<CaddyInstanceInfo> = emptyList(),
) {
    val statusLabel: String
        get() = when (state) {
            CaddyDiscoveryState.NOT_DETECTED -> "Caddy not detected"
            CaddyDiscoveryState.DISCOVERED -> "Caddy routes discovered"
            CaddyDiscoveryState.PARTIAL -> "Caddy discovery partial"
            CaddyDiscoveryState.CONFIG_MOUNT_MISSING -> "Caddy config mount missing"
            CaddyDiscoveryState.CONFIG_UNAVAILABLE -> "Caddy config unavailable"
            CaddyDiscoveryState.CONFIG_TOO_LARGE -> "Caddy config too large"
            CaddyDiscoveryState.CONFIG_INVALID, CaddyDiscoveryState.MALFORMED -> "Caddy config invalid"
            CaddyDiscoveryState.AMBIGUOUS -> "Caddy discovery ambiguous"
            CaddyDiscoveryState.UNKNOWN -> "Caddy status unknown"
        }
}

data class CaddyInstanceInfo(
    val containerId: String,
    val containerName: String,
    val image: String? = null,
    val state: CaddyDiscoveryState = CaddyDiscoveryState.UNKNOWN,
    val routes: List<CaddyRouteInfo> = emptyList(),
)

data class CaddyRouteInfo(
    val hosts: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val hostless: Boolean = false,
    val hostMatcherUnresolved: Boolean = false,
    val upstreams: List<CaddyUpstreamInfo> = emptyList(),
    val dynamicUpstreams: Boolean = false,
) {
    val canonicalKey: String
        get() = listOf(
            hosts.sorted().joinToString(","),
            paths.sorted().joinToString(","),
            hostless.toString(),
            hostMatcherUnresolved.toString(),
            upstreams.sortedBy { it.canonicalKey }.joinToString(",") { it.canonicalKey },
            dynamicUpstreams.toString(),
        ).joinToString("|")
}

enum class CaddyUpstreamSourceState { STATIC, UNRESOLVED }

data class CaddyUpstreamInfo(
    val dial: String? = null,
    val sourceState: CaddyUpstreamSourceState = CaddyUpstreamSourceState.UNRESOLVED,
) {
    val canonicalKey: String get() = "${sourceState.name}:${dial.orEmpty()}"
}

fun normalizeDomain(value: String): String? {
    val trimmed = value.trim().removeSuffix(".").lowercase()
    if (trimmed.isEmpty() || trimmed.length > 253 || trimmed.any { it.isWhitespace() || it.code < 32 }) return null
    if (trimmed.any { it in "{}$/\\@" }) return null
    val wildcard = trimmed.startsWith("*.")
    val domain = if (wildcard) trimmed.removePrefix("*.") else trimmed
    if (domain.isEmpty() || domain.split('.').any { label -> label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' || label.any { !it.isLetterOrDigit() && it != '-' } }) return null
    return if (wildcard) "*.$domain" else domain
}
