package com.ilkimgul.vpsgraph.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Parses only schemaVersion 1's documented allowlist; all other helper fields are discarded. */
object DockerHelperParser {
    fun parse(output: String): DockerDiscoveryResult {
        if (output.length > MAX_HELPER_JSON_CHARS) return failed()
        val root = runCatching { Json.parseToJsonElement(output).jsonObject }.getOrElse {
            return failed()
        }
        if (root.int("schemaVersion") != 1) return failed()
        if (root.bool("ok") != true) return helperFailure(root)

        return runCatching {
            val engine = DockerEngineInfo(root.objectValue("engine").string("version"))
            DockerDiscoveryResult(
                state = DockerDiscoveryState.AVAILABLE,
                engine = engine,
                composeProjects = root.array("composeProjects").mapNotNull(::project).distinctBy { it.project }.sortedBy { it.project },
                containers = root.array("containers").map(::container).sortedWith(compareBy(DockerContainerInfo::name, DockerContainerInfo::id)),
                caddy = parseCaddy(root["caddy"]),
                systemd = parseSystemd(root["systemd"]),
                listeners = parseListeners(root["listeners"]),
            )
        }.getOrElse { failed() }
    }

    private fun helperFailure(root: JsonObject): DockerDiscoveryResult = when (root.objectValue("error").string("code")) {
        "DOCKER_UNAVAILABLE", "DOCKER_DAEMON_UNAVAILABLE", "UNTRUSTED_DOCKER_EXECUTABLE" -> unavailable()
        else -> failed()
    }

    private fun project(value: kotlinx.serialization.json.JsonElement): DockerComposeProjectInfo? {
        val objectValue = value as? JsonObject ?: return null
        val project = objectValue.string("project")?.takeIf(String::isNotBlank) ?: return null
        return DockerComposeProjectInfo(project, objectValue.string("workingDirectory"), objectValue.string("configFiles"))
    }

    private fun container(value: kotlinx.serialization.json.JsonElement): DockerContainerInfo {
        val objectValue = value as? JsonObject ?: error("Malformed container")
        val id = objectValue.string("id")?.takeIf(String::isNotBlank) ?: error("Missing container id")
        val name = objectValue.string("name")?.takeIf(String::isNotBlank) ?: error("Missing container name")
        return DockerContainerInfo(
            id = id,
            name = name,
            image = objectValue.string("image"),
            state = objectValue.string("state"),
            restartPolicy = objectValue.string("restartPolicy"),
            compose = compose(objectValue["compose"]),
            ports = objectValue.array("ports").mapNotNull(::port).sortedWith(compareBy(DockerPortInfo::containerPort, DockerPortInfo::protocol, { it.hostIp.orEmpty() }, { it.hostPort.orEmpty() })),
            mounts = objectValue.array("mounts").mapNotNull(::mount).sortedWith(compareBy(DockerMountInfo::destination, DockerMountInfo::type)),
            networks = objectValue.array("networks").mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.distinct().sorted(),
        )
    }

    private fun compose(value: kotlinx.serialization.json.JsonElement?): DockerContainerComposeInfo? {
        val objectValue = value as? JsonObject ?: return null
        return DockerContainerComposeInfo(
            project = objectValue.string("project"),
            service = objectValue.string("service"),
            workingDirectory = objectValue.string("workingDirectory"),
            configFiles = objectValue.string("configFiles"),
        ).takeIf { it.project != null || it.service != null || it.workingDirectory != null || it.configFiles != null }
    }

    private fun port(value: kotlinx.serialization.json.JsonElement): DockerPortInfo? {
        val objectValue = value as? JsonObject ?: return null
        val containerPort = objectValue.string("containerPort") ?: return null
        val protocol = objectValue.string("protocol") ?: return null
        return DockerPortInfo(containerPort, protocol, objectValue.string("hostIp"), objectValue.string("hostPort"))
    }

    private fun mount(value: kotlinx.serialization.json.JsonElement): DockerMountInfo? {
        val objectValue = value as? JsonObject ?: return null
        val type = objectValue.string("type") ?: return null
        val destination = objectValue.string("destination") ?: return null
        return DockerMountInfo(type, objectValue.string("source"), destination, objectValue.string("volumeName"), objectValue.bool("readOnly") ?: false)
    }

    private fun parseCaddy(value: kotlinx.serialization.json.JsonElement?): CaddyDiscoveryResult {
        if (value == null) return CaddyDiscoveryResult()
        val caddy = value as? JsonObject ?: return CaddyDiscoveryResult(CaddyDiscoveryState.MALFORMED)
        val state = caddy.string("status")?.toCaddyState() ?: return CaddyDiscoveryResult(CaddyDiscoveryState.MALFORMED)
        val rawInstances = caddy["instances"] as? JsonArray ?: return CaddyDiscoveryResult(CaddyDiscoveryState.MALFORMED)
        var malformed = false
        val instances = rawInstances.mapNotNull { raw ->
            runCatching { caddyInstance(raw) }.getOrElse {
                malformed = true
                null
            }
        }.sortedWith(compareBy(CaddyInstanceInfo::containerName, CaddyInstanceInfo::containerId))
        val effectiveState = when {
            malformed && instances.isEmpty() -> CaddyDiscoveryState.MALFORMED
            malformed -> CaddyDiscoveryState.PARTIAL
            state == CaddyDiscoveryState.DISCOVERED && instances.any { it.state == CaddyDiscoveryState.PARTIAL } -> CaddyDiscoveryState.PARTIAL
            else -> state
        }
        return CaddyDiscoveryResult(effectiveState, instances)
    }

    private fun parseSystemd(value: kotlinx.serialization.json.JsonElement?): SystemdDiscoveryInfo {
        if (value == null) return SystemdDiscoveryInfo()
        val section = value as? JsonObject ?: return SystemdDiscoveryInfo(OptionalDiscoveryState.MALFORMED)
        val state = section.string("status")?.toOptionalDiscoveryState() ?: return SystemdDiscoveryInfo(OptionalDiscoveryState.MALFORMED)
        val rawServices = section["services"]?.let { it as? JsonArray ?: return SystemdDiscoveryInfo(OptionalDiscoveryState.MALFORMED) } ?: JsonArray(emptyList())
        var malformed = false
        val services = rawServices.mapNotNull { raw -> runCatching { systemdService(raw) }.getOrElse { malformed = true; null } }
            .distinctBy(SystemdServiceInfo::unit).sortedBy(SystemdServiceInfo::unit)
        val effectiveState = when {
            malformed && services.isEmpty() -> OptionalDiscoveryState.MALFORMED
            malformed -> OptionalDiscoveryState.PARTIAL
            else -> state
        }
        return SystemdDiscoveryInfo(effectiveState, statusReasons(section), services)
    }

    private fun systemdService(value: kotlinx.serialization.json.JsonElement): SystemdServiceInfo {
        val service = value as? JsonObject ?: error("Malformed Systemd service")
        val unit = canonicalSystemdUnit(service.string("unit")) ?: error("Malformed Systemd unit")
        return SystemdServiceInfo(
            unit = unit,
            description = service.safeString("description", 512),
            loadState = service.safeString("loadState", 128),
            activeState = service.safeString("activeState", 128),
            subState = service.safeString("subState", 128),
            unitFileState = service.safeString("unitFileState", 128),
            serviceType = service.safeString("serviceType", 128),
            user = service.safeString("user", 128),
            group = service.safeString("group", 128),
            controlGroup = service.safeString("controlGroup", 512)?.takeIf { it.startsWith('/') },
        )
    }

    private fun parseListeners(value: kotlinx.serialization.json.JsonElement?): ListenerDiscoveryInfo {
        if (value == null) return ListenerDiscoveryInfo()
        val section = value as? JsonObject ?: return ListenerDiscoveryInfo(OptionalDiscoveryState.MALFORMED)
        val state = section.string("status")?.toOptionalDiscoveryState() ?: return ListenerDiscoveryInfo(OptionalDiscoveryState.MALFORMED)
        val rawItems = section["items"]?.let { it as? JsonArray ?: return ListenerDiscoveryInfo(OptionalDiscoveryState.MALFORMED) } ?: JsonArray(emptyList())
        var malformed = false
        val items = rawItems.mapNotNull { raw -> runCatching { listener(raw) }.getOrElse { malformed = true; null } }
            .distinctBy(HostListenerInfo::canonicalKey)
            .sortedWith(compareBy(HostListenerInfo::protocol, HostListenerInfo::localAddress, HostListenerInfo::port))
        val effectiveState = when {
            malformed && items.isEmpty() -> OptionalDiscoveryState.MALFORMED
            malformed -> OptionalDiscoveryState.PARTIAL
            else -> state
        }
        return ListenerDiscoveryInfo(effectiveState, statusReasons(section), items)
    }

    private fun listener(value: kotlinx.serialization.json.JsonElement): HostListenerInfo {
        val item = value as? JsonObject ?: error("Malformed host listener")
        val protocol = item.string("protocol")?.lowercase()?.takeIf { it == "tcp" || it == "udp" } ?: error("Malformed listener protocol")
        val address = item.safeString("localAddress", 128)?.takeIf { it.none(Char::isWhitespace) } ?: error("Malformed listener address")
        val port = item.int("port")?.takeIf { it in 1..65535 } ?: error("Malformed listener port")
        val wildcard = item.bool("wildcard") ?: error("Malformed listener wildcard state")
        val loopback = item.bool("loopback") ?: error("Malformed listener loopback state")
        val ownership = item.string("ownershipState")?.let { runCatching { ListenerOwnershipState.valueOf(it) }.getOrDefault(ListenerOwnershipState.UNKNOWN) }
            ?: ListenerOwnershipState.UNKNOWN
        return HostListenerInfo(
            protocol = protocol,
            localAddress = address,
            port = port,
            wildcard = wildcard,
            loopback = loopback,
            ownershipState = ownership,
            systemdUnit = canonicalSystemdUnit(item.string("systemdUnit")),
            processName = item.safeString("processName", 128)?.takeIf { it.none(Char::isWhitespace) },
        )
    }

    private fun statusReasons(section: JsonObject): List<DiscoveryStatusReason> = section.array("statusReasons")
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .map { runCatching { DiscoveryStatusReason.valueOf(it) }.getOrDefault(DiscoveryStatusReason.UNKNOWN) }
        .distinct().sortedBy(DiscoveryStatusReason::name)

    private fun String.toOptionalDiscoveryState(): OptionalDiscoveryState =
        runCatching { OptionalDiscoveryState.valueOf(this) }.getOrDefault(OptionalDiscoveryState.UNKNOWN)

    private fun canonicalSystemdUnit(value: String?): String? {
        val unit = value?.takeIf { it.length in 1..255 && it.endsWith(".service") && it.none(Char::isWhitespace) && it.none { character -> character.code < 32 || character.code == 127 } } ?: return null
        return unit.takeIf { SYSTEMD_UNIT.matches(it) && SYSTEMD_ESCAPE.findAll(it).none { match -> match.groupValues[1].toInt(16).let { byte -> byte < 32 || byte == 127 } } }
    }

    private fun JsonObject.safeString(name: String, maxLength: Int): String? =
        string(name)?.takeIf { it.isNotBlank() && it.length <= maxLength && it.none { character -> character.code < 32 || character.code == 127 } }

    private fun caddyInstance(value: kotlinx.serialization.json.JsonElement): CaddyInstanceInfo {
        val instance = value as? JsonObject ?: error("Malformed Caddy instance")
        val id = instance.string("containerId")?.takeIf(String::isNotBlank) ?: error("Missing Caddy container id")
        val name = instance.string("containerName")?.takeIf(String::isNotBlank) ?: error("Missing Caddy container name")
        var malformedRoute = false
        val routes = instance.array("routes").mapNotNull { raw -> runCatching { caddyRoute(raw) }.getOrElse { malformedRoute = true; null } }
            .distinctBy(CaddyRouteInfo::canonicalKey).sortedBy(CaddyRouteInfo::canonicalKey)
        val state = instance.string("status")?.toCaddyState() ?: CaddyDiscoveryState.UNKNOWN
        return CaddyInstanceInfo(id, name, instance.string("image"), if (malformedRoute && state == CaddyDiscoveryState.DISCOVERED) CaddyDiscoveryState.PARTIAL else state, routes)
    }

    private fun caddyRoute(value: kotlinx.serialization.json.JsonElement): CaddyRouteInfo {
        val route = value as? JsonObject ?: error("Malformed Caddy route")
        val hosts = route.array("hosts").mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let(::normalizeDomain) }
            .distinct().sorted()
        val paths = route.array("paths").mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?.takeIf { path -> path.startsWith('/') && path.length <= 2048 && path.none { character -> character.code < 32 } }
        }.distinct().sorted()
        val upstreams = route.array("upstreams").mapNotNull { raw ->
            val upstream = raw as? JsonObject ?: return@mapNotNull null
            upstream.string("dial")?.takeIf(String::isNotBlank)?.let { CaddyUpstreamInfo(it, CaddyUpstreamSourceState.STATIC) }
                ?: upstream.string("status")?.takeIf { it == "UNRESOLVED" }?.let { CaddyUpstreamInfo() }
        }.distinctBy(CaddyUpstreamInfo::canonicalKey).sortedBy(CaddyUpstreamInfo::canonicalKey)
        return CaddyRouteInfo(
            hosts = hosts,
            paths = paths,
            hostless = route.bool("hostless") == true,
            hostMatcherUnresolved = route.string("hostMatcher") == "UNRESOLVED",
            upstreams = upstreams,
            dynamicUpstreams = route.string("dynamicUpstreams") == "UNSUPPORTED",
        )
    }

    private fun String.toCaddyState(): CaddyDiscoveryState = runCatching { CaddyDiscoveryState.valueOf(this) }.getOrDefault(CaddyDiscoveryState.UNKNOWN)

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
    private fun unavailable() = DockerDiscoveryResult(DockerDiscoveryState.DOCKER_UNAVAILABLE)
    private fun failed() = DockerDiscoveryResult(DockerDiscoveryState.SCAN_FAILED)

    private val SYSTEMD_UNIT = Regex("[A-Za-z0-9](?:[A-Za-z0-9@_.:-]|\\\\x[0-9A-Fa-f]{2})*\\.service")
    private val SYSTEMD_ESCAPE = Regex("\\\\x([0-9A-Fa-f]{2})")
    private const val MAX_HELPER_JSON_CHARS = 8 * 1024 * 1024
}
