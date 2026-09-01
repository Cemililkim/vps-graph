package com.ilkimgul.vpsgraph.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

const val SNAPSHOT_SCHEMA_VERSION = 1

@Serializable
data class InfrastructureSnapshot(
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val snapshotId: String,
    val serverId: String,
    @Serializable(with = InstantAsStringSerializer::class)
    val capturedAt: Instant,
    val graphFingerprint: String,
    val content: InfrastructureSnapshotContent,
)

@Serializable
data class InfrastructureSnapshotContent(
    val nodes: List<SnapshotNode>,
    val edges: List<SnapshotEdge>,
    val discoveryQuality: List<SnapshotDiscoveryQuality>,
)

@Serializable
data class SnapshotNode(
    val id: String,
    val label: String,
    val type: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class SnapshotEdge(
    val id: String,
    val source: String,
    val target: String,
    val relation: String,
)

@Serializable
data class SnapshotDiscoveryQuality(
    val subsystem: String,
    val state: String,
    val reasons: List<String> = emptyList(),
)

object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UTC instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
