package com.ilkimgul.vpsgraph.core

object InfrastructureDiffEngine {
    fun compare(previous: InfrastructureSnapshot?, current: InfrastructureSnapshot): InfrastructureDiff {
        if (current.schemaVersion != SNAPSHOT_SCHEMA_VERSION || previous?.schemaVersion?.let { it != SNAPSHOT_SCHEMA_VERSION } == true) {
            return terminal(previous, current, InfrastructureDiffStatus.INCOMPATIBLE_SNAPSHOT_SCHEMA, complete = false)
        }
        if (previous == null) return terminal(null, current, InfrastructureDiffStatus.NO_BASELINE, complete = false)
        if (previous.serverId != current.serverId) return terminal(previous, current, InfrastructureDiffStatus.SERVER_MISMATCH, complete = false)
        if (previous.capturedAt > current.capturedAt) return terminal(previous, current, InfrastructureDiffStatus.INVALID_TIME_ORDER, complete = false)
        if (!valid(previous.content) || !valid(current.content)) return terminal(previous, current, InfrastructureDiffStatus.INVALID_SNAPSHOT, complete = false)
        if (previous.graphFingerprint == current.graphFingerprint) return terminal(previous, current, InfrastructureDiffStatus.NO_CHANGES)

        val previousQuality = qualityBySubsystem(previous.content)
        val currentQuality = qualityBySubsystem(current.content)
        val previousNodes = previous.content.nodes.associateBy(SnapshotNode::id)
        val currentNodes = current.content.nodes.associateBy(SnapshotNode::id)
        val uncertainties = mutableSetOf<UncertaintyKey>()

        REMOTE_SUBSYSTEMS.filterNot { isComplete(it, currentQuality) }.forEach {
            uncertainties += UncertaintyKey(it, DiffUncertaintyReason.CURRENT_DISCOVERY_INCOMPLETE)
        }

        val nodeChanges = mutableListOf<NodeChange>()
        (currentNodes.keys - previousNodes.keys).forEach { id ->
            val node = currentNodes.getValue(id)
            val required = requiredSubsystems(node.type)
            val evidence = additionEvidence(required, previousQuality)
            if (evidence == ChangeEvidence.NEWLY_OBSERVED) required.filterNot { isComplete(it, previousQuality) }.forEach {
                uncertainties += UncertaintyKey(it, DiffUncertaintyReason.PREVIOUS_DISCOVERY_INCOMPLETE)
            }
            nodeChanges += nodeChange(NodeChangeKind.ADDED, evidence, node)
        }
        (previousNodes.keys - currentNodes.keys).forEach { id ->
            val node = previousNodes.getValue(id)
            nodeChanges += nodeChange(NodeChangeKind.REMOVED, removalEvidence(requiredSubsystems(node.type), currentQuality), node)
        }
        (previousNodes.keys intersect currentNodes.keys).forEach { id ->
            val before = previousNodes.getValue(id)
            val after = currentNodes.getValue(id)
            val fields = fieldChanges(before, after, currentQuality)
            if (fields.isNotEmpty()) nodeChanges += nodeChange(NodeChangeKind.MODIFIED, ChangeEvidence.CONFIRMED, after, fields)
        }

        val previousEdges = previous.content.edges.associateBy(SnapshotEdge::id)
        val currentEdges = current.content.edges.associateBy(SnapshotEdge::id)
        val relationshipChanges = mutableListOf<RelationshipChange>()
        (currentEdges.keys - previousEdges.keys).forEach { id ->
            val edge = currentEdges.getValue(id)
            val required = requiredSubsystems(edge, currentNodes)
            val evidence = additionEvidence(required, previousQuality)
            if (evidence == ChangeEvidence.NEWLY_OBSERVED) required.filterNot { isComplete(it, previousQuality) }.forEach {
                uncertainties += UncertaintyKey(it, DiffUncertaintyReason.PREVIOUS_DISCOVERY_INCOMPLETE)
            }
            relationshipChanges += relationshipChange(RelationshipChangeKind.RELATIONSHIP_ADDED, evidence, edge)
        }
        (previousEdges.keys - currentEdges.keys).forEach { id ->
            val edge = previousEdges.getValue(id)
            relationshipChanges += relationshipChange(
                RelationshipChangeKind.RELATIONSHIP_REMOVED,
                removalEvidence(requiredSubsystems(edge, previousNodes), currentQuality),
                edge,
            )
        }

        val orderedNodes = nodeChanges.sortedWith(compareBy(NodeChange::kind, NodeChange::nodeType, NodeChange::nodeId))
        val orderedRelationships = relationshipChanges.sortedWith(compareBy(RelationshipChange::kind, { it.edge.relation }, { it.edge.id }))
        val orderedUncertainties = uncertainties.map { key ->
            DiffUncertainty(key.subsystem, key.reason, resourceTypesFor(key.subsystem))
        }.sortedWith(compareBy(DiffUncertainty::subsystem, DiffUncertainty::reason))
        val unconfirmed = orderedNodes.count { it.evidence == ChangeEvidence.UNCONFIRMED_REMOVAL } +
            orderedRelationships.count { it.evidence == ChangeEvidence.UNCONFIRMED_REMOVAL }
        val confirmedChanges = orderedNodes.count { it.evidence != ChangeEvidence.UNCONFIRMED_REMOVAL } +
            orderedRelationships.count { it.evidence != ChangeEvidence.UNCONFIRMED_REMOVAL }
        val complete = orderedUncertainties.isEmpty() && unconfirmed == 0
        val summary = DiffSummary(
            addedNodes = orderedNodes.count { it.kind == NodeChangeKind.ADDED },
            removedNodes = orderedNodes.count { it.kind == NodeChangeKind.REMOVED && it.evidence == ChangeEvidence.CONFIRMED },
            modifiedNodes = orderedNodes.count { it.kind == NodeChangeKind.MODIFIED },
            addedRelationships = orderedRelationships.count { it.kind == RelationshipChangeKind.RELATIONSHIP_ADDED },
            removedRelationships = orderedRelationships.count { it.kind == RelationshipChangeKind.RELATIONSHIP_REMOVED && it.evidence == ChangeEvidence.CONFIRMED },
            uncertainComparisons = orderedUncertainties.size + unconfirmed,
            hasChanges = confirmedChanges > 0,
            isComplete = complete,
        )
        val status = when {
            !complete -> InfrastructureDiffStatus.PARTIAL
            summary.hasChanges -> InfrastructureDiffStatus.CHANGED
            else -> InfrastructureDiffStatus.NO_CHANGES
        }
        return InfrastructureDiff(
            previous.snapshotId,
            current.snapshotId,
            previous.capturedAt.toString(),
            current.capturedAt.toString(),
            status,
            summary,
            orderedNodes,
            orderedRelationships,
            orderedUncertainties,
        )
    }

    private fun fieldChanges(
        before: SnapshotNode,
        after: SnapshotNode,
        currentQuality: Map<DiffSubsystem, String>,
    ): List<FieldChange> = buildList {
        if (before.type != after.type) add(FieldChange("type", before.type, after.type))
        if (before.label != after.label && before.type != NodeType.DOCKER_ENGINE.name) add(FieldChange("label", before.label, after.label))
        (before.metadata.keys + after.metadata.keys).toSortedSet().filterNot { it in QUALITY_METADATA_KEYS }.forEach { key ->
            val oldValue = before.metadata[key]
            val newValue = after.metadata[key]
            if (oldValue == newValue) return@forEach
            val ownerComplete = (requiredSubsystems(before.type) + requiredSubsystems(after.type)).all { isComplete(it, currentQuality) }
            val fieldComplete = fieldSubsystems(after.type, key).all { isComplete(it, currentQuality) }
            if (fieldComplete && (newValue != null || ownerComplete)) add(FieldChange("metadata:$key", oldValue, newValue))
        }
    }.sortedBy(FieldChange::field)

    private fun fieldSubsystems(type: String, key: String): Set<DiffSubsystem> = when {
        type == NodeType.SYSTEMD_SERVICE.name && key == "listener count" -> setOf(DiffSubsystem.HOST_LISTENERS)
        type == NodeType.HOST_LISTENER.name && key in setOf("ownership state", "systemd unit") -> setOf(DiffSubsystem.HOST_LISTENERS, DiffSubsystem.SYSTEMD)
        type == NodeType.DOMAIN.name && key == "resolved host services" -> setOf(DiffSubsystem.DOCKER, DiffSubsystem.CADDY, DiffSubsystem.HOST_LISTENERS, DiffSubsystem.SYSTEMD)
        type == NodeType.DOMAIN.name && key == "resolved containers" -> setOf(DiffSubsystem.DOCKER, DiffSubsystem.CADDY)
        type == NodeType.UPSTREAM.name && key in HOST_RESOLUTION_FIELDS -> setOf(DiffSubsystem.DOCKER, DiffSubsystem.CADDY, DiffSubsystem.HOST_LISTENERS, DiffSubsystem.SYSTEMD)
        else -> emptySet()
    }

    private fun nodeChange(kind: NodeChangeKind, evidence: ChangeEvidence, node: SnapshotNode, fields: List<FieldChange> = emptyList()) =
        NodeChange("node:${kind.name}:${node.id}", kind, evidence, node.id, node.type, node.label, fields)

    private fun relationshipChange(kind: RelationshipChangeKind, evidence: ChangeEvidence, edge: SnapshotEdge) =
        RelationshipChange("relationship:${kind.name}:${edge.id}", kind, evidence, edge)

    private fun additionEvidence(required: Set<DiffSubsystem>, previousQuality: Map<DiffSubsystem, String>): ChangeEvidence =
        if (required.all { isComplete(it, previousQuality) }) ChangeEvidence.CONFIRMED else ChangeEvidence.NEWLY_OBSERVED

    private fun removalEvidence(required: Set<DiffSubsystem>, currentQuality: Map<DiffSubsystem, String>): ChangeEvidence =
        if (required.all { isComplete(it, currentQuality) }) ChangeEvidence.CONFIRMED else ChangeEvidence.UNCONFIRMED_REMOVAL

    private fun requiredSubsystems(edge: SnapshotEdge, nodes: Map<String, SnapshotNode>): Set<DiffSubsystem> =
        requiredSubsystems(nodes.getValue(edge.source).type) + requiredSubsystems(nodes.getValue(edge.target).type)

    private fun requiredSubsystems(type: String): Set<DiffSubsystem> = when (type) {
        NodeType.SERVER.name, NodeType.DIRECTORY.name -> setOf(DiffSubsystem.BASE_HOST)
        NodeType.DOCKER_ENGINE.name, NodeType.DOCKER_COMPOSE_PROJECT.name, NodeType.DOCKER_CONTAINER.name,
        NodeType.PORT.name, NodeType.NETWORK.name, NodeType.MOUNT.name -> setOf(DiffSubsystem.DOCKER)
        NodeType.REVERSE_PROXY.name, NodeType.DOMAIN.name, NodeType.UPSTREAM.name -> setOf(DiffSubsystem.DOCKER, DiffSubsystem.CADDY)
        NodeType.SYSTEMD_SERVICE.name -> setOf(DiffSubsystem.SYSTEMD)
        NodeType.HOST_LISTENER.name -> setOf(DiffSubsystem.HOST_LISTENERS)
        else -> REMOTE_SUBSYSTEMS
    }

    private fun qualityBySubsystem(content: InfrastructureSnapshotContent): Map<DiffSubsystem, String> =
        content.discoveryQuality.mapNotNull { quality ->
            when (quality.subsystem) {
                "DOCKER" -> DiffSubsystem.DOCKER
                "CADDY" -> DiffSubsystem.CADDY
                "SYSTEMD" -> DiffSubsystem.SYSTEMD
                "HOST_LISTENERS" -> DiffSubsystem.HOST_LISTENERS
                else -> null
            }?.let { it to quality.state }
        }.toMap()

    private fun isComplete(subsystem: DiffSubsystem, quality: Map<DiffSubsystem, String>): Boolean = when (subsystem) {
        DiffSubsystem.BASE_HOST -> true
        DiffSubsystem.DOCKER -> quality[subsystem] == "AVAILABLE"
        DiffSubsystem.CADDY -> quality[subsystem] in setOf("DISCOVERED", "NOT_DETECTED")
        DiffSubsystem.SYSTEMD, DiffSubsystem.HOST_LISTENERS -> quality[subsystem] == "DISCOVERED"
    }

    private fun resourceTypesFor(subsystem: DiffSubsystem): List<String> = NodeType.entries
        .filter { subsystem in requiredSubsystems(it.name) }
        .map { it.name }
        .sorted()

    private fun valid(content: InfrastructureSnapshotContent): Boolean {
        if (content.nodes.map(SnapshotNode::id).distinct().size != content.nodes.size) return false
        if (content.edges.map(SnapshotEdge::id).distinct().size != content.edges.size) return false
        if (content.discoveryQuality.map(SnapshotDiscoveryQuality::subsystem).distinct().size != content.discoveryQuality.size) return false
        val ids = content.nodes.mapTo(hashSetOf(), SnapshotNode::id)
        return content.edges.all { it.source in ids && it.target in ids }
    }

    private fun terminal(
        previous: InfrastructureSnapshot?,
        current: InfrastructureSnapshot,
        status: InfrastructureDiffStatus,
        complete: Boolean = true,
    ) = InfrastructureDiff(
        previous?.snapshotId,
        current.snapshotId,
        previous?.capturedAt?.toString(),
        current.capturedAt.toString(),
        status,
        DiffSummary(isComplete = complete),
    )

    private data class UncertaintyKey(val subsystem: DiffSubsystem, val reason: DiffUncertaintyReason)

    private val REMOTE_SUBSYSTEMS = setOf(DiffSubsystem.DOCKER, DiffSubsystem.CADDY, DiffSubsystem.SYSTEMD, DiffSubsystem.HOST_LISTENERS)
    private val QUALITY_METADATA_KEYS = setOf(
        "discovery status",
        "docker discovery state",
        "caddy discovery status",
        "caddy discovery state",
        "systemd discovery status",
        "systemd status reasons",
        "listener discovery status",
        "listener status reasons",
    )
    private val HOST_RESOLUTION_FIELDS = setOf(
        "host resolution",
        "target kind",
        "matching port",
        "host resolution reason",
        "host service",
        "eligible listeners",
        "candidate services",
    )
}
