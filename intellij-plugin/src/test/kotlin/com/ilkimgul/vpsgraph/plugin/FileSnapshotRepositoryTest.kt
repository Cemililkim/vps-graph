package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfraEdge
import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.InfraNode
import com.ilkimgul.vpsgraph.core.NodeType
import com.ilkimgul.vpsgraph.core.RelationType
import com.ilkimgul.vpsgraph.core.SshTarget
import com.ilkimgul.vpsgraph.core.SnapshotProjection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class FileSnapshotRepositoryTest {
    private val root = createTempDirectory("vps-graph-snapshots-")

    @AfterTest fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test fun `history survives repository recreation and supports latest list and load`() {
        val repository = FileSnapshotRepository(root)
        val first = snapshot(SERVER_A, SNAPSHOT_1, "first", "2026-08-30T10:00:00Z")
        val second = snapshot(SERVER_A, SNAPSHOT_2, "second", "2026-08-30T11:00:00Z")

        assertIs<SnapshotSaveResult.Saved>(repository.save(first))
        assertIs<SnapshotSaveResult.Saved>(repository.save(second))
        val recreated = FileSnapshotRepository(root)
        assertEquals(listOf(SNAPSHOT_2, SNAPSHOT_1), recreated.listMetadata(SERVER_A).map { it.snapshotId })
        assertEquals(SNAPSHOT_2, recreated.latest(SERVER_A)?.snapshotId)
        assertEquals(first, recreated.load(SERVER_A, SNAPSHOT_1))
    }

    @Test fun `consecutive identical content deduplicates and retention keeps newest changed snapshots`() {
        val repository = FileSnapshotRepository(root, retentionLimit = 2)
        val first = snapshot(SERVER_A, SNAPSHOT_1, "same", "2026-08-30T10:00:00Z")
        val duplicate = snapshot(SERVER_A, SNAPSHOT_2, "same", "2026-08-30T11:00:00Z")
        val changed = snapshot(SERVER_A, SNAPSHOT_2, "changed", "2026-08-30T12:00:00Z")
        val newest = snapshot(SERVER_A, SNAPSHOT_3, "newest", "2026-08-30T13:00:00Z")

        repository.save(first)
        assertIs<SnapshotSaveResult.Deduplicated>(repository.save(duplicate))
        repository.save(changed)
        repository.save(newest)

        assertEquals(listOf(SNAPSHOT_3, SNAPSHOT_2), repository.listMetadata(SERVER_A).map { it.snapshotId })
        assertNull(repository.load(SERVER_A, SNAPSHOT_1))
        assertEquals(SNAPSHOT_3, repository.latest(SERVER_A)?.snapshotId)
    }

    @Test fun `missing or corrupt index rebuilds from valid orphan snapshots`() {
        val repository = FileSnapshotRepository(root)
        val first = snapshot(SERVER_A, SNAPSHOT_1, "first", "2026-08-30T10:00:00Z")
        repository.save(first)
        val directory = snapshotDirectory(SERVER_A)
        Files.delete(directory.resolve("index.json"))
        assertEquals(SNAPSHOT_1, repository.latest(SERVER_A)?.snapshotId)

        Files.writeString(directory.resolve("index.json"), "{broken")
        val orphan = snapshot(SERVER_A, SNAPSHOT_2, "orphan", "2026-08-30T11:00:00Z")
        Files.writeString(directory.resolve("$SNAPSHOT_2.json"), Json.encodeToString(orphan))
        assertEquals(listOf(SNAPSHOT_2, SNAPSHOT_1), repository.listMetadata(SERVER_A).map { it.snapshotId })

        val index = Files.readString(directory.resolve("index.json"))
        Files.writeString(directory.resolve("index.json"), index.replaceFirst("\"entries\":[", "\"entries\":[{\"bad\":true},"))
        assertEquals(2, repository.listMetadata(SERVER_A).size)

        val parsed = Json.parseToJsonElement(Files.readString(directory.resolve("index.json"))).jsonObject
        val entries = parsed.getValue("entries").jsonArray
        Files.writeString(directory.resolve("index.json"), JsonObject(parsed + ("entries" to JsonArray(entries + entries.first()))).toString())
        assertEquals(listOf(SNAPSHOT_2, SNAPSHOT_1), repository.listMetadata(SERVER_A).map { it.snapshotId })
    }

    @Test fun `corrupt oversized and unsupported snapshots are isolated while safe unknown fields are tolerated`() {
        val repository = FileSnapshotRepository(root)
        val first = snapshot(SERVER_A, SNAPSHOT_1, "first", "2026-08-30T10:00:00Z")
        val second = snapshot(SERVER_A, SNAPSHOT_2, "second", "2026-08-30T11:00:00Z")
        repository.save(first)
        repository.save(second)
        val directory = snapshotDirectory(SERVER_A)

        Files.writeString(directory.resolve("$SNAPSHOT_2.json"), "{corrupt")
        assertEquals(SNAPSHOT_1, repository.latest(SERVER_A)?.snapshotId)

        val firstPath = directory.resolve("$SNAPSHOT_1.json")
        Files.writeString(firstPath, Files.readString(firstPath).replaceFirst("{", "{\"futureField\":true,"))
        assertEquals(SNAPSHOT_1, repository.load(SERVER_A, SNAPSHOT_1)?.snapshotId)

        val future = snapshot(SERVER_A, SNAPSHOT_3, "future", "2026-08-30T12:00:00Z")
        Files.writeString(directory.resolve("$SNAPSHOT_3.json"), Json.encodeToString(future.copy(schemaVersion = 99)))
        assertNull(repository.load(SERVER_A, SNAPSHOT_3))

        val oversizedRepository = FileSnapshotRepository(root, maxSnapshotBytes = 100)
        assertFailsWith<IllegalArgumentException> { oversizedRepository.save(snapshot(SERVER_B, SNAPSHOT_1, "too-large", "2026-08-30T10:00:00Z")) }
        val oversizedDirectory = snapshotDirectory(SERVER_B)
        Files.createDirectories(oversizedDirectory)
        Files.writeString(oversizedDirectory.resolve("$SNAPSHOT_1.json"), "x".repeat(101))
        assertNull(oversizedRepository.load(SERVER_B, SNAPSHOT_1))
    }

    @Test fun `temp artifacts missing files duplicate IDs and unsafe path text cannot poison history`() {
        val repository = FileSnapshotRepository(root)
        val first = snapshot(SERVER_A, SNAPSHOT_1, "first", "2026-08-30T10:00:00Z")
        repository.save(first)
        val directory = snapshotDirectory(SERVER_A)
        Files.writeString(directory.resolve(".$SNAPSHOT_2.json.tmp"), "partial")
        assertEquals(1, repository.listMetadata(SERVER_A).size)

        Files.delete(directory.resolve("$SNAPSHOT_1.json"))
        assertTrue(repository.listMetadata(SERVER_A).isEmpty())
        assertFailsWith<IllegalArgumentException> { repository.listMetadata("../outside") }
        assertFailsWith<IllegalArgumentException> { repository.load(SERVER_A, "../index") }

        val duplicateNode = InfraNode("duplicate", "duplicate", NodeType.SERVER)
        val invalidGraph = InfraGraph(listOf(duplicateNode, duplicateNode), emptyList())
        assertFailsWith<IllegalArgumentException> { SnapshotProjection.capture(invalidGraph, SERVER_A) }
    }

    @Test fun `same-server concurrent saves serialize and different server histories stay isolated`() {
        val repository = FileSnapshotRepository(root)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val saves = listOf(
                executor.submit { repository.save(snapshot(SERVER_A, SNAPSHOT_1, "one", "2026-08-30T10:00:00Z")) },
                executor.submit { repository.save(snapshot(SERVER_A, SNAPSHOT_2, "two", "2026-08-30T11:00:00Z")) },
                executor.submit { repository.save(snapshot(SERVER_B, SNAPSHOT_3, "other", "2026-08-30T12:00:00Z")) },
            )
            saves.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(setOf(SNAPSHOT_1, SNAPSHOT_2), repository.listMetadata(SERVER_A).mapTo(hashSetOf()) { it.snapshotId })
        assertEquals(listOf(SNAPSHOT_3), repository.listMetadata(SERVER_B).map { it.snapshotId })
        assertNotEquals(repository.latest(SERVER_A)?.serverId, repository.latest(SERVER_B)?.serverId)
    }

    @Test fun `server identity is opaque stable across restart and excludes private key path`() {
        val target = SshTarget("VPS.Example", 22, "vpsgraph", "/keys/first")
        val first = ServerIdentityStore(root).resolve(target)
        val sameProfile = ServerIdentityStore(root).resolve(target.copy(host = "vps.example", privateKeyPath = "/keys/second"))
        val differentProfile = ServerIdentityStore(root).resolve(target.copy(username = "deploy"))

        assertEquals(first, sameProfile)
        assertNotEquals(first, differentProfile)
        val file = Files.readString(root.resolve("server-identities.json"))
        assertTrue("VPS.Example" !in file && "/keys/first" !in file && "/keys/second" !in file)
    }

    @Test fun `successful capture persists and a repeated canonical graph deduplicates`() {
        val repository = FileSnapshotRepository(root)
        val identities = ServerIdentityStore(root)
        val persistence = SnapshotPersistence(identities, repository)
        val target = SshTarget("vps.example", 22, "vpsgraph", "/keys/key")
        val graph = snapshot(SERVER_A, SNAPSHOT_1, "live", "2026-08-30T10:00:00Z").content.let { content ->
            InfraGraph(
                content.nodes.map { InfraNode(it.id, it.label, NodeType.valueOf(it.type), it.metadata) },
                content.edges.map { InfraEdge(it.id, it.source, it.target, RelationType.valueOf(it.relation)) },
            )
        }

        assertIs<SnapshotSaveResult.Saved>(persistence.capture(target, graph))
        assertIs<SnapshotSaveResult.Deduplicated>(SnapshotPersistence(ServerIdentityStore(root), FileSnapshotRepository(root)).capture(target, graph))
        val serverId = ServerIdentityStore(root).resolve(target)
        assertEquals(1, FileSnapshotRepository(root).listMetadata(serverId).size)
    }

    private fun snapshot(serverId: String, snapshotId: String, label: String, capturedAt: String) = SnapshotProjection.capture(
        graph = InfraGraph(
            nodes = listOf(
                InfraNode("server:stable", label, NodeType.SERVER, mapOf("hostname" to "vps", "ssh host" to "vps.example", "ssh username" to "vpsgraph")),
                InfraNode("server:stable:docker", "Docker", NodeType.DOCKER_ENGINE, mapOf("docker discovery state" to "AVAILABLE", "caddy discovery state" to "NOT_DETECTED")),
            ),
            edges = listOf(InfraEdge("server:stable:runs:docker", "server:stable", "server:stable:docker", RelationType.RUNS)),
        ),
        serverId = serverId,
        snapshotId = snapshotId,
        capturedAt = Instant.parse(capturedAt),
    )

    private fun snapshotDirectory(serverId: String): Path = root.resolve("snapshots").resolve(serverId)

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SNAPSHOT_1 = "11111111-1111-4111-8111-111111111111"
        const val SNAPSHOT_2 = "22222222-2222-4222-8222-222222222222"
        const val SNAPSHOT_3 = "33333333-3333-4333-8333-333333333333"
    }
}
