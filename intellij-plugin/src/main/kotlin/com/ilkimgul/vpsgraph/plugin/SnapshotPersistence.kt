package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.InfrastructureSnapshot
import com.ilkimgul.vpsgraph.core.SshTarget
import com.ilkimgul.vpsgraph.core.SnapshotProjection
import com.intellij.openapi.application.PathManager
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SnapshotPersistence(
    private val identities: ServerIdentityStore,
    private val snapshots: SnapshotRepository,
) {
    fun capture(target: SshTarget, graph: InfraGraph): SnapshotSaveResult = captureContext(target, graph).result

    fun captureContext(target: SshTarget, graph: InfraGraph): SnapshotCaptureContext {
        val serverId = identities.resolve(target)
        val previous = snapshots.latest(serverId)
        val current = SnapshotProjection.capture(graph, serverId)
        return SnapshotCaptureContext(serverId, previous, current, snapshots.save(current))
    }

    fun history(serverId: String): List<SnapshotMetadata> = snapshots.listMetadata(serverId)

    fun load(serverId: String, snapshotId: String): InfrastructureSnapshot? = snapshots.load(serverId, snapshotId)

    companion object {
        fun local(): SnapshotPersistence {
            val root = PathManager.getSystemDir().resolve("vps-graph")
            return SnapshotPersistence(ServerIdentityStore(root), FileSnapshotRepository(root))
        }
    }
}

data class SnapshotCaptureContext(
    val serverId: String,
    val previous: InfrastructureSnapshot?,
    val current: InfrastructureSnapshot,
    val result: SnapshotSaveResult,
)

class ServerIdentityStore(private val root: Path) {
    fun resolve(target: SshTarget): String = identityLocks
        .computeIfAbsent(root.toAbsolutePath().normalize().toString()) { ReentrantLock() }
        .withLock {
            val key = connectionKey(target)
            val current = read()
            current[key]?.let { return@withLock it }
            require(current.size < MAX_SERVER_IDENTITIES) { "Local server identity limit exceeded." }
            val serverId = UUID.randomUUID().toString()
            write(current + (key to serverId))
            serverId
        }

    private fun read(): Map<String, String> {
        val path = root.resolve(FILE_NAME)
        if (!Files.exists(path)) return emptyMap()
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_FILE_BYTES) { "Local server identity index is invalid." }
        val index = runCatching { parseIndex(Files.readString(path, StandardCharsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("Local server identity index could not be read.") }
        require(index.size <= MAX_SERVER_IDENTITIES) { "Local server identity index is unsupported." }
        require(index.all { (key, value) -> HASH_PATTERN.matches(key) && UUID_PATTERN.matches(value) }) { "Local server identity index is invalid." }
        return index
    }

    private fun write(entries: Map<String, String>) {
        Files.createDirectories(root)
        val target = root.resolve(FILE_NAME)
        val temporary = root.resolve(".$FILE_NAME.tmp")
        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("entries", buildJsonObject { entries.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }
        val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Local server identity index exceeds its size limit." }
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun connectionKey(target: SshTarget): String {
        val canonical = "${target.host.trim().lowercase()}\u0000${target.port}\u0000${target.username.trim()}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun parseIndex(value: String): Map<String, String> {
        val root = json.parseToJsonElement(value).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported server identity schema." }
        return root["entries"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: error("Missing server identities.")
    }

    private companion object {
        const val FILE_NAME = "server-identities.json"
        const val MAX_SERVER_IDENTITIES = 1_000
        const val MAX_FILE_BYTES = 256L * 1024
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val identityLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
