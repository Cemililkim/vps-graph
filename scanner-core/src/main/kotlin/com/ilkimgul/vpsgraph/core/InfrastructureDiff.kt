package com.ilkimgul.vpsgraph.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
data class InfrastructureDiff(
    val previousSnapshotId: String?,
    val currentSnapshotId: String,
    val previousCapturedAt: String?,
    val currentCapturedAt: String,
    val status: InfrastructureDiffStatus,
    val summary: DiffSummary,
    @EncodeDefault val nodeChanges: List<NodeChange> = emptyList(),
    @EncodeDefault val relationshipChanges: List<RelationshipChange> = emptyList(),
    @EncodeDefault val uncertainties: List<DiffUncertainty> = emptyList(),
)

@Serializable
enum class InfrastructureDiffStatus {
    NO_BASELINE,
    NO_CHANGES,
    CHANGED,
    PARTIAL,
    INCOMPATIBLE_SNAPSHOT_SCHEMA,
    SERVER_MISMATCH,
    INVALID_TIME_ORDER,
    INVALID_SNAPSHOT,
}

@Serializable
data class DiffSummary(
    @EncodeDefault val addedNodes: Int = 0,
    @EncodeDefault val removedNodes: Int = 0,
    @EncodeDefault val modifiedNodes: Int = 0,
    @EncodeDefault val addedRelationships: Int = 0,
    @EncodeDefault val removedRelationships: Int = 0,
    @EncodeDefault val uncertainComparisons: Int = 0,
    @EncodeDefault val hasChanges: Boolean = false,
    @EncodeDefault val isComplete: Boolean = true,
)

@Serializable
enum class NodeChangeKind { ADDED, REMOVED, MODIFIED }

@Serializable
enum class RelationshipChangeKind { RELATIONSHIP_ADDED, RELATIONSHIP_REMOVED }

@Serializable
enum class ChangeEvidence { CONFIRMED, NEWLY_OBSERVED, UNCONFIRMED_REMOVAL }

@Serializable
data class NodeChange(
    val id: String,
    val kind: NodeChangeKind,
    val evidence: ChangeEvidence,
    val nodeId: String,
    val nodeType: String,
    val label: String,
    @EncodeDefault val fields: List<FieldChange> = emptyList(),
)

@Serializable
data class RelationshipChange(
    val id: String,
    val kind: RelationshipChangeKind,
    val evidence: ChangeEvidence,
    val edge: SnapshotEdge,
)

@Serializable
data class FieldChange(
    val field: String,
    val before: String? = null,
    val after: String? = null,
)

@Serializable
enum class DiffSubsystem { BASE_HOST, DOCKER, CADDY, SYSTEMD, HOST_LISTENERS }

@Serializable
enum class DiffUncertaintyReason { CURRENT_DISCOVERY_INCOMPLETE, PREVIOUS_DISCOVERY_INCOMPLETE }

@Serializable
data class DiffUncertainty(
    val subsystem: DiffSubsystem,
    val reason: DiffUncertaintyReason,
    val affectedResourceTypes: List<String>,
)
