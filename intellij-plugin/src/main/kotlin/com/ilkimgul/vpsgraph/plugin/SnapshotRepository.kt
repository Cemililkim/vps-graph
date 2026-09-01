package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfrastructureSnapshot
import com.ilkimgul.vpsgraph.core.SNAPSHOT_SCHEMA_VERSION
import com.ilkimgul.vpsgraph.core.SnapshotProjection
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.name
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface SnapshotRepository {
    fun save(snapshot: InfrastructureSnapshot): SnapshotSaveResult
    fun latest(serverId: String): InfrastructureSnapshot?
    fun listMetadata(serverId: String): List<SnapshotMetadata>
    fun load(serverId: String, snapshotId: String): InfrastructureSnapshot?
}

sealed interface SnapshotSaveResult {
    data class Saved(val metadata: SnapshotMetadata) : SnapshotSaveResult
    data class Deduplicated(val existing: SnapshotMetadata) : SnapshotSaveResult
}

data class SnapshotMetadata(
    val snapshotId: String,
    val capturedAt: Instant,
    val graphFingerprint: String,
    val schemaVersion: Int,
)

class FileSnapshotRepository(
    private val root: Path,
    private val retentionLimit: Int = DEFAULT_RETENTION,
    private val maxSnapshotBytes: Long = MAX_SNAPSHOT_BYTES,
) : SnapshotRepository {
    init {
        require(retentionLimit in 1..MAX_SNAPSHOTS_INDEXED) { "Retention must be within the local index bound." }
        require(maxSnapshotBytes > 0) { "Snapshot size bound must be positive." }
    }

    override fun save(snapshot: InfrastructureSnapshot): SnapshotSaveResult = withServerLock(snapshot.serverId) {
        validate(snapshot)
        val directory = serverDirectory(snapshot.serverId)
        Files.createDirectories(directory)
        val current = readOrRecoverIndex(snapshot.serverId, directory)
        val latest = current.firstOrNull()?.let { loadSnapshot(directory.resolve("${it.snapshotId}.json"), snapshot.serverId, it.snapshotId) }
        if (latest?.graphFingerprint == snapshot.graphFingerprint) {
            return@withServerLock SnapshotSaveResult.Deduplicated(latest.toMetadata())
        }

        val bytes = json.encodeToString(snapshot).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxSnapshotBytes) { "Snapshot exceeds the local persistence size limit." }
        atomicWrite(directory.resolve("${snapshot.snapshotId}.json"), bytes)

        val retained = (current.filterNot { it.snapshotId == snapshot.snapshotId } + snapshot.toMetadata())
            .sortedWith(metadataOrder)
            .take(retentionLimit)
        val retainedIds = retained.mapTo(hashSetOf(), SnapshotMetadata::snapshotId)
        current.filterNot { it.snapshotId in retainedIds }.forEach { Files.deleteIfExists(directory.resolve("${it.snapshotId}.json")) }
        writeIndex(snapshot.serverId, directory, retained)
        SnapshotSaveResult.Saved(snapshot.toMetadata())
    }

    override fun latest(serverId: String): InfrastructureSnapshot? = withServerLock(serverId) {
        val directory = serverDirectory(serverId)
        if (!Files.isDirectory(directory)) return@withServerLock null
        val entries = readOrRecoverIndex(serverId, directory)
        entries.firstNotNullOfOrNull { loadSnapshot(directory.resolve("${it.snapshotId}.json"), serverId, it.snapshotId) }
            .also { loaded ->
                if (loaded == null && entries.isNotEmpty()) writeIndex(serverId, directory, emptyList())
                else if (loaded != null && loaded.snapshotId != entries.firstOrNull()?.snapshotId) recoverIndex(serverId, directory)
            }
    }

    override fun listMetadata(serverId: String): List<SnapshotMetadata> = withServerLock(serverId) {
        val directory = serverDirectory(serverId)
        if (!Files.isDirectory(directory)) emptyList() else readOrRecoverIndex(serverId, directory)
    }

    override fun load(serverId: String, snapshotId: String): InfrastructureSnapshot? = withServerLock(serverId) {
        requireUuid(snapshotId, "snapshot ID")
        val directory = serverDirectory(serverId)
        if (!Files.isDirectory(directory)) return@withServerLock null
        loadSnapshot(directory.resolve("$snapshotId.json"), serverId, snapshotId).also { loaded ->
            if (loaded == null && Files.exists(directory.resolve("$snapshotId.json"))) recoverIndex(serverId, directory)
        }
    }

    private fun readOrRecoverIndex(serverId: String, directory: Path): List<SnapshotMetadata> {
        val indexed = readIndex(directory.resolve(INDEX_FILE), serverId)
        val fileIds = snapshotFiles(directory).mapTo(hashSetOf()) { it.name.removeSuffix(".json") }
        if (indexed == null || indexed.distinctBy(SnapshotMetadata::snapshotId).size != indexed.size || indexed.mapTo(hashSetOf(), SnapshotMetadata::snapshotId) != fileIds) {
            return recoverIndex(serverId, directory)
        }
        return indexed.sortedWith(metadataOrder).take(MAX_SNAPSHOTS_INDEXED)
    }

    private fun readIndex(path: Path, serverId: String): List<SnapshotMetadata>? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_INDEX_BYTES) return@runCatching null
        val root = json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
        if (root["schemaVersion"]?.jsonPrimitive?.intOrNull != 1 || root["serverId"]?.jsonPrimitive?.content != serverId) return@runCatching null
        val entries = root["entries"]?.jsonArray ?: return@runCatching null
        if (entries.size > MAX_SNAPSHOTS_INDEXED) return@runCatching null
        entries.mapNotNull { element ->
            runCatching { parseMetadata(element.jsonObject) }.getOrNull()?.takeIf(::validMetadata)
        }
    }.getOrNull()

    private fun recoverIndex(serverId: String, directory: Path): List<SnapshotMetadata> {
        val valid = snapshotFiles(directory)
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0) }
            .take(MAX_SNAPSHOT_FILES_SCANNED)
            .mapNotNull { path -> loadSnapshot(path, serverId, path.name.removeSuffix(".json"))?.toMetadata()?.let { path to it } }
            .distinctBy { it.second.snapshotId }
            .sortedWith { first, second -> metadataOrder.compare(first.second, second.second) }
        val retained = valid
            .take(retentionLimit)
        retained.mapTo(hashSetOf()) { it.second.snapshotId }.let { retainedIds ->
            valid.filterNot { it.second.snapshotId in retainedIds }.forEach { Files.deleteIfExists(it.first) }
        }
        val entries = retained
            .map { it.second }
            .sortedWith(metadataOrder)
        writeIndex(serverId, directory, entries)
        return entries
    }

    private fun snapshotFiles(directory: Path): List<Path> = Files.list(directory).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) && path.name.endsWith(".json") && path.name != INDEX_FILE && UUID_PATTERN.matches(path.name.removeSuffix(".json"))
        }.limit(MAX_SNAPSHOT_FILES_SCANNED.toLong() + 1).toList()
    }

    private fun loadSnapshot(path: Path, serverId: String, snapshotId: String): InfrastructureSnapshot? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > maxSnapshotBytes) return@runCatching null
        val snapshot = json.decodeFromString<InfrastructureSnapshot>(Files.readString(path, StandardCharsets.UTF_8))
        if (snapshot.serverId != serverId || snapshot.snapshotId != snapshotId) return@runCatching null
        validate(snapshot)
        snapshot
    }.getOrNull()

    private fun validate(snapshot: InfrastructureSnapshot) {
        require(snapshot.schemaVersion == SNAPSHOT_SCHEMA_VERSION) { "Unsupported snapshot schema." }
        requireUuid(snapshot.serverId, "server ID")
        requireUuid(snapshot.snapshotId, "snapshot ID")
        require(FINGERPRINT_PATTERN.matches(snapshot.graphFingerprint)) { "Invalid graph fingerprint." }
        require(snapshot.content.nodes.size <= MAX_NODES) { "Snapshot node limit exceeded." }
        require(snapshot.content.edges.size <= MAX_EDGES) { "Snapshot edge limit exceeded." }
        require(snapshot.content.discoveryQuality.size <= MAX_DISCOVERY_QUALITY) { "Snapshot discovery-quality limit exceeded." }
        require(snapshot.content.nodes.map { it.id }.distinct().size == snapshot.content.nodes.size) { "Duplicate snapshot node ID." }
        require(snapshot.content.edges.map { it.id }.distinct().size == snapshot.content.edges.size) { "Duplicate snapshot edge ID." }
        val nodeIds = snapshot.content.nodes.mapTo(hashSetOf()) { it.id }
        snapshot.content.nodes.forEach { node ->
            bounded(node.id, MAX_ID_LENGTH); bounded(node.label, MAX_LABEL_LENGTH); bounded(node.type, MAX_TYPE_LENGTH)
            require(node.metadata.size <= MAX_METADATA_ENTRIES) { "Snapshot metadata entry limit exceeded." }
            node.metadata.forEach { (key, value) -> bounded(key, MAX_METADATA_KEY_LENGTH); bounded(value, MAX_METADATA_VALUE_LENGTH) }
        }
        snapshot.content.edges.forEach { edge ->
            bounded(edge.id, MAX_ID_LENGTH); bounded(edge.source, MAX_ID_LENGTH); bounded(edge.target, MAX_ID_LENGTH); bounded(edge.relation, MAX_TYPE_LENGTH)
            require(edge.source in nodeIds && edge.target in nodeIds) { "Snapshot edge endpoint is missing." }
        }
        snapshot.content.discoveryQuality.forEach { quality ->
            bounded(quality.subsystem, MAX_TYPE_LENGTH); bounded(quality.state, MAX_TYPE_LENGTH)
            require(quality.reasons.size <= MAX_STATUS_REASONS) { "Snapshot status reason limit exceeded." }
            quality.reasons.forEach { bounded(it, MAX_TYPE_LENGTH) }
        }
        require(SnapshotProjection.fingerprint(snapshot.content) == snapshot.graphFingerprint) { "Snapshot fingerprint does not match content." }
    }

    private fun validMetadata(metadata: SnapshotMetadata): Boolean =
        UUID_PATTERN.matches(metadata.snapshotId) && FINGERPRINT_PATTERN.matches(metadata.graphFingerprint) && metadata.schemaVersion == SNAPSHOT_SCHEMA_VERSION

    private fun parseMetadata(value: JsonObject) = SnapshotMetadata(
        snapshotId = value.getValue("snapshotId").jsonPrimitive.content,
        capturedAt = Instant.parse(value.getValue("capturedAt").jsonPrimitive.content),
        graphFingerprint = value.getValue("graphFingerprint").jsonPrimitive.content,
        schemaVersion = value.getValue("schemaVersion").jsonPrimitive.intOrNull ?: error("Invalid schema version"),
    )

    private fun metadataJson(value: SnapshotMetadata) = buildJsonObject {
        put("snapshotId", value.snapshotId)
        put("capturedAt", value.capturedAt.toString())
        put("graphFingerprint", value.graphFingerprint)
        put("schemaVersion", value.schemaVersion)
    }

    private fun serverDirectory(serverId: String): Path {
        requireUuid(serverId, "server ID")
        return root.resolve("snapshots").resolve(serverId)
    }

    private fun writeIndex(serverId: String, directory: Path, entries: List<SnapshotMetadata>) {
        val index = buildJsonObject {
            put("schemaVersion", 1)
            put("serverId", serverId)
            put("entries", JsonArray(entries.sortedWith(metadataOrder).map(::metadataJson)))
        }
        val bytes = index.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_INDEX_BYTES) { "Snapshot index exceeds the local persistence size limit." }
        atomicWrite(directory.resolve(INDEX_FILE), bytes)
    }

    private fun atomicWrite(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.tmp")
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

    private fun bounded(value: String, limit: Int) = require(value.isNotEmpty() && value.length <= limit) { "Snapshot string is outside persistence bounds." }
    private fun requireUuid(value: String, label: String) = require(UUID_PATTERN.matches(value)) { "Invalid $label." }
    private fun InfrastructureSnapshot.toMetadata() = SnapshotMetadata(snapshotId, capturedAt, graphFingerprint, schemaVersion)

    private fun <T> withServerLock(serverId: String, action: () -> T): T {
        requireUuid(serverId, "server ID")
        val key = root.toAbsolutePath().normalize().resolve(serverId).toString()
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock(action)
    }

    companion object {
        const val DEFAULT_RETENTION = 50
        const val MAX_NODES = 25_000
        const val MAX_EDGES = 100_000
        const val MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024
        const val MAX_SNAPSHOTS_INDEXED = 50
        private const val MAX_SNAPSHOT_FILES_SCANNED = 200
        private const val MAX_INDEX_BYTES = 512L * 1024
        private const val MAX_DISCOVERY_QUALITY = 32
        private const val MAX_METADATA_ENTRIES = 64
        private const val MAX_STATUS_REASONS = 32
        private const val MAX_ID_LENGTH = 1024
        private const val MAX_LABEL_LENGTH = 4096
        private const val MAX_TYPE_LENGTH = 128
        private const val MAX_METADATA_KEY_LENGTH = 256
        private const val MAX_METADATA_VALUE_LENGTH = 16_384
        private const val INDEX_FILE = "index.json"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
        private val metadataOrder = compareByDescending<SnapshotMetadata> { it.capturedAt }.thenByDescending { it.snapshotId }
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
