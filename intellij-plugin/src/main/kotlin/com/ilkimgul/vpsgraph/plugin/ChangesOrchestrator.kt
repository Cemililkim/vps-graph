package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.InfrastructureDiff
import com.ilkimgul.vpsgraph.core.InfrastructureDiffEngine
import com.ilkimgul.vpsgraph.core.InfrastructureDiffStatus
import com.ilkimgul.vpsgraph.core.InfrastructureSnapshot
import com.ilkimgul.vpsgraph.core.SnapshotNode
import com.ilkimgul.vpsgraph.core.SshTarget
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class ChangesResponse(
    val ok: Boolean,
    val payload: ChangesPayload? = null,
    val error: ChangesError? = null,
) {
    companion object {
        fun success(payload: ChangesPayload) = ChangesResponse(true, payload = payload)
        fun failure(code: String, message: String) = ChangesResponse(false, error = ChangesError(code, message))
    }
}

@Serializable
data class ChangesError(val code: String, val message: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChangesPayload(
    @EncodeDefault val schemaVersion: Int = 1,
    val comparison: SnapshotComparison,
    val diff: InfrastructureDiff,
    val resources: List<ChangeResourceSnapshot>,
    val history: List<SnapshotHistoryItem>,
)

@Serializable
data class SnapshotComparison(
    val previous: SnapshotDisplayMetadata? = null,
    val current: SnapshotDisplayMetadata,
)

@Serializable
data class SnapshotDisplayMetadata(
    val snapshotId: String,
    val capturedAt: String,
    val fingerprint: String,
    val persisted: Boolean,
)

@Serializable
data class SnapshotHistoryItem(
    val snapshotId: String,
    val capturedAt: String,
    val fingerprint: String,
)

@Serializable
data class ChangeResourceSnapshot(
    val nodeId: String,
    val before: SnapshotNode? = null,
    val after: SnapshotNode? = null,
)

class ChangesOrchestrator(private val persistence: SnapshotPersistence) {
    @Volatile private var activeServerId: String? = null
    @Volatile private var latest: ChangesResponse = ChangesResponse.failure("NOT_CONNECTED", "Scan a server to view local change history.")

    fun capture(target: SshTarget, graph: InfraGraph): ChangesResponse {
        val capture = persistence.captureContext(target, graph)
        activeServerId = capture.serverId
        return ChangesResponse.success(payload(capture.previous, capture.current, capture.serverId, capture.result is SnapshotSaveResult.Saved)).also { latest = it }
    }

    fun current(): ChangesResponse = latest

    fun compare(previousSnapshotId: String, currentSnapshotId: String): ChangesResponse {
        if (!SNAPSHOT_ID.matches(previousSnapshotId) || !SNAPSHOT_ID.matches(currentSnapshotId)) {
            return ChangesResponse.failure("INVALID_SNAPSHOT_ID", "The selected snapshot comparison is invalid.")
        }
        val serverId = activeServerId ?: return ChangesResponse.failure("NOT_CONNECTED", "Scan a server to view local change history.")
        val available = persistence.history(serverId).mapTo(hashSetOf(), SnapshotMetadata::snapshotId)
        if (previousSnapshotId !in available || currentSnapshotId !in available) {
            return ChangesResponse.failure("SNAPSHOT_UNAVAILABLE", "A selected snapshot is no longer available in local history.")
        }
        val previous = persistence.load(serverId, previousSnapshotId)
        val current = persistence.load(serverId, currentSnapshotId)
        if (previous == null || current == null) {
            return ChangesResponse.failure("SNAPSHOT_UNAVAILABLE", "A selected snapshot could not be read from local history.")
        }
        if (previous.capturedAt > current.capturedAt) {
            return ChangesResponse.failure("INVALID_COMPARISON", "Choose an older snapshot to compare with the newer snapshot.")
        }
        val payload = payload(previous, current, serverId, currentPersisted = true)
        return if (payload.diff.status in TERMINAL_FAILURES) {
            ChangesResponse.failure("COMPARISON_FAILED", "The selected snapshots could not be compared safely.")
        } else ChangesResponse.success(payload)
    }

    private fun payload(
        previous: InfrastructureSnapshot?,
        current: InfrastructureSnapshot,
        serverId: String,
        currentPersisted: Boolean,
    ): ChangesPayload {
        val diff = InfrastructureDiffEngine.compare(previous, current)
        val previousNodes = previous?.content?.nodes?.associateBy(SnapshotNode::id).orEmpty()
        val currentNodes = current.content.nodes.associateBy(SnapshotNode::id)
        val relevantIds = buildSet {
            diff.nodeChanges.forEach { add(it.nodeId) }
            diff.relationshipChanges.forEach { add(it.edge.source); add(it.edge.target) }
        }
        return ChangesPayload(
            comparison = SnapshotComparison(previous?.display(persisted = true), current.display(currentPersisted)),
            diff = diff,
            resources = relevantIds.sorted().map { ChangeResourceSnapshot(it, previousNodes[it], currentNodes[it]) },
            history = persistence.history(serverId).map { it.historyItem() },
        )
    }

    private fun InfrastructureSnapshot.display(persisted: Boolean) = SnapshotDisplayMetadata(
        snapshotId,
        capturedAt.toString(),
        graphFingerprint.take(FINGERPRINT_DISPLAY_LENGTH),
        persisted,
    )

    private fun SnapshotMetadata.historyItem() = SnapshotHistoryItem(
        snapshotId,
        capturedAt.toString(),
        graphFingerprint.take(FINGERPRINT_DISPLAY_LENGTH),
    )

    companion object {
        fun local() = ChangesOrchestrator(SnapshotPersistence.local())

        private const val FINGERPRINT_DISPLAY_LENGTH = 10
        private val SNAPSHOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        private val TERMINAL_FAILURES = setOf(
            InfrastructureDiffStatus.INCOMPATIBLE_SNAPSHOT_SCHEMA,
            InfrastructureDiffStatus.SERVER_MISMATCH,
            InfrastructureDiffStatus.INVALID_TIME_ORDER,
            InfrastructureDiffStatus.INVALID_SNAPSHOT,
        )
    }
}
