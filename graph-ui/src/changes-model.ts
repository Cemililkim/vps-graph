import type { ChangeResourceSnapshot, ChangesPayload, ChangesResponse, DiffSummary, InfrastructureDiff, NodeChange, RelationshipChange, SnapshotNode } from './types'

export type ChangeFilter = 'ALL' | 'ADDED' | 'MODIFIED' | 'REMOVED' | 'RELATIONSHIPS' | 'UNCERTAIN'

export interface RelationshipPresentation {
  id: string
  relation: string
  subject: SnapshotNode | null
  subjectInCurrent: boolean
  beforeTarget: SnapshotNode | null
  afterTarget: SnapshotNode | null
  records: RelationshipChange[]
  uncertain: boolean
}

export interface NodeChangeGroups {
  added: NodeChange[]
  modified: NodeChange[]
  removed: NodeChange[]
  uncertain: NodeChange[]
}

export function parseChangesResponse(serialized: string): ChangesResponse {
  try {
    return normalizeChangesResponse(JSON.parse(serialized))
  } catch {
    return { ok: false, error: { code: 'INVALID_CHANGES_PAYLOAD', message: 'The local change comparison could not be read.' } }
  }
}

export function normalizeChangesResponse(value: unknown): ChangesResponse {
  const response = object(value)
  if (!response || typeof response.ok !== 'boolean') return unavailable()
  if (!response.ok) {
    const error = object(response.error)
    return { ok: false, error: { code: text(error?.code, 'CHANGES_UNAVAILABLE'), message: text(error?.message, 'Local change history is unavailable.') } }
  }
  const payload = normalizePayload(response.payload)
  return payload ? { ok: true, payload } : unavailable()
}

function normalizePayload(value: unknown): ChangesPayload | null {
  const payload = object(value)
  if (!payload || payload.schemaVersion !== 1 || !Array.isArray(payload.resources) || !Array.isArray(payload.history)) return null
  const comparison = object(payload.comparison)
  const current = snapshotMetadata(comparison?.current)
  const diff = normalizeDiff(payload.diff)
  if (!comparison || !current || !diff) return null
  const previous = comparison.previous === undefined || comparison.previous === null ? null : snapshotMetadata(comparison.previous)
  if (comparison.previous !== undefined && comparison.previous !== null && !previous) return null
  return {
    schemaVersion: 1,
    comparison: { current, ...(previous ? { previous } : {}) },
    diff,
    resources: payload.resources.map(normalizeResource).filter((resource): resource is ChangeResourceSnapshot => resource !== null),
    history: payload.history.map(snapshotHistory).filter((entry): entry is ChangesPayload['history'][number] => entry !== null),
  }
}

function normalizeDiff(value: unknown): InfrastructureDiff | null {
  const diff = object(value)
  const summary = normalizeSummary(diff?.summary)
  if (!diff || !summary || typeof diff.currentSnapshotId !== 'string' || typeof diff.currentCapturedAt !== 'string' || typeof diff.status !== 'string') return null
  return {
    previousSnapshotId: nullableText(diff.previousSnapshotId),
    currentSnapshotId: diff.currentSnapshotId,
    previousCapturedAt: nullableText(diff.previousCapturedAt),
    currentCapturedAt: diff.currentCapturedAt,
    status: diff.status,
    summary,
    nodeChanges: array(diff.nodeChanges).map(normalizeNodeChange).filter((change): change is NodeChange => change !== null),
    relationshipChanges: array(diff.relationshipChanges).map(normalizeRelationshipChange).filter((change): change is RelationshipChange => change !== null),
    uncertainties: array(diff.uncertainties).map(normalizeUncertainty).filter((uncertainty): uncertainty is InfrastructureDiff['uncertainties'][number] => uncertainty !== null),
  }
}

function normalizeSummary(value: unknown): DiffSummary | null {
  const summary = object(value)
  if (!summary) return null
  return {
    addedNodes: count(summary.addedNodes), removedNodes: count(summary.removedNodes), modifiedNodes: count(summary.modifiedNodes),
    addedRelationships: count(summary.addedRelationships), removedRelationships: count(summary.removedRelationships), uncertainComparisons: count(summary.uncertainComparisons),
    hasChanges: summary.hasChanges === true, isComplete: summary.isComplete !== false,
  }
}

function normalizeNodeChange(value: unknown): NodeChange | null {
  const change = object(value)
  if (!change || typeof change.id !== 'string' || typeof change.nodeId !== 'string') return null
  return { id: change.id, nodeId: change.nodeId, kind: text(change.kind, 'UNKNOWN'), evidence: text(change.evidence, 'UNKNOWN'), nodeType: text(change.nodeType, 'UNKNOWN'), label: text(change.label, 'Unknown resource'), fields: array(change.fields).map(normalizeField).filter((field): field is NodeChange['fields'][number] => field !== null) }
}

function normalizeRelationshipChange(value: unknown): RelationshipChange | null {
  const change = object(value); const edge = object(change?.edge)
  if (!change || !edge || typeof change.id !== 'string' || typeof edge.id !== 'string' || typeof edge.source !== 'string' || typeof edge.target !== 'string') return null
  return { id: change.id, kind: text(change.kind, 'UNKNOWN'), evidence: text(change.evidence, 'UNKNOWN'), edge: { id: edge.id, source: edge.source, target: edge.target, relation: text(edge.relation, 'UNKNOWN') } }
}

function normalizeUncertainty(value: unknown): InfrastructureDiff['uncertainties'][number] | null {
  const uncertainty = object(value)
  if (!uncertainty) return null
  return { subsystem: text(uncertainty.subsystem, 'UNKNOWN'), reason: text(uncertainty.reason, 'UNKNOWN'), affectedResourceTypes: array(uncertainty.affectedResourceTypes).filter((item): item is string => typeof item === 'string') }
}

function normalizeResource(value: unknown): ChangeResourceSnapshot | null {
  const resource = object(value)
  if (!resource || typeof resource.nodeId !== 'string') return null
  const before = resource.before === undefined || resource.before === null ? null : snapshotNode(resource.before)
  const after = resource.after === undefined || resource.after === null ? null : snapshotNode(resource.after)
  if ((resource.before !== undefined && resource.before !== null && !before) || (resource.after !== undefined && resource.after !== null && !after)) return null
  return { nodeId: resource.nodeId, ...(before ? { before } : {}), ...(after ? { after } : {}) }
}

function snapshotNode(value: unknown): SnapshotNode | null {
  const node = object(value)
  if (!node || typeof node.id !== 'string' || typeof node.label !== 'string' || typeof node.type !== 'string') return null
  const metadata = object(node.metadata)
  if (node.metadata !== undefined && !metadata) return null
  const safeMetadata: Record<string, string> = {}
  Object.entries(metadata ?? {}).forEach(([key, entry]) => {
    if (typeof entry === 'string') safeMetadata[key] = entry
  })
  return { id: node.id, label: node.label, type: node.type, metadata: safeMetadata }
}

function snapshotMetadata(value: unknown): ChangesPayload['comparison']['current'] | null {
  const snapshot = object(value)
  return snapshot && typeof snapshot.snapshotId === 'string' && typeof snapshot.capturedAt === 'string' && typeof snapshot.fingerprint === 'string' && typeof snapshot.persisted === 'boolean'
    ? { snapshotId: snapshot.snapshotId, capturedAt: snapshot.capturedAt, fingerprint: snapshot.fingerprint, persisted: snapshot.persisted }
    : null
}

function snapshotHistory(value: unknown): ChangesPayload['history'][number] | null {
  const snapshot = object(value)
  return snapshot && typeof snapshot.snapshotId === 'string' && typeof snapshot.capturedAt === 'string' && typeof snapshot.fingerprint === 'string'
    ? { snapshotId: snapshot.snapshotId, capturedAt: snapshot.capturedAt, fingerprint: snapshot.fingerprint }
    : null
}

function normalizeField(value: unknown): NodeChange['fields'][number] | null {
  const field = object(value)
  return field && typeof field.field === 'string' ? { field: field.field, before: nullableText(field.before), after: nullableText(field.after) } : null
}

function object(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function text(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback
}

function nullableText(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function count(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0
}

function unavailable(): ChangesResponse {
  return { ok: false, error: { code: 'INVALID_CHANGES_PAYLOAD', message: 'The local change comparison could not be read.' } }
}

export function confirmedChangeCount(payload: ChangesPayload | null): number {
  if (!payload) return 0
  const summary = payload.diff.summary
  return summary.addedNodes + summary.modifiedNodes + summary.removedNodes + summary.addedRelationships + summary.removedRelationships
}

export function nodeChangeGroups(payload: ChangesPayload, query = ''): NodeChangeGroups {
  const matches = payload.diff.nodeChanges.filter((change) => nodeMatches(change, query))
  return {
    added: matches.filter((change) => change.kind === 'ADDED'),
    modified: matches.filter((change) => change.kind === 'MODIFIED'),
    removed: matches.filter((change) => change.kind === 'REMOVED' && change.evidence !== 'UNCONFIRMED_REMOVAL'),
    uncertain: matches.filter((change) => change.evidence === 'UNCONFIRMED_REMOVAL'),
  }
}

export function stateCopy(status: string): { title: string; body: string } {
  if (status === 'NO_BASELINE') return { title: 'Baseline captured', body: 'VPS Graph will compare the next changed scan with this snapshot.' }
  if (status === 'NO_CHANGES') return { title: 'No infrastructure changes since the previous scan.', body: 'The current canonical infrastructure fingerprint matches the latest saved baseline.' }
  return { title: 'This comparison is unavailable.', body: 'The selected snapshots could not be compared safely.' }
}

export function overviewChangeSummary(payload: ChangesPayload): { title: string; detail: string } {
  if (payload.diff.status === 'NO_BASELINE') return { title: 'Baseline captured', detail: 'The next changed scan will be compared.' }
  if (payload.diff.status === 'NO_CHANGES') return { title: 'No changes since previous scan', detail: 'Current infrastructure matches the latest baseline.' }
  return {
    title: 'Changes since last scan',
    detail: `+${payload.diff.summary.addedNodes} added · ~${payload.diff.summary.modifiedNodes} modified · −${payload.diff.summary.removedNodes} removed`,
  }
}

export function comparisonCandidates(payload: ChangesPayload) {
  const latest = payload.history[0]
  return { latest, older: payload.history.slice(1) }
}

export function resourceTypeLabel(type: string): string {
  return RESOURCE_TYPES[type] ?? humanize(type || 'resource')
}

export function relationLabel(relation: string): string {
  return RELATIONS[relation] ?? humanize(relation || 'relationship')
}

export function fieldLabel(field: string): string {
  return humanize(field.replace(/^metadata:/, ''))
}

export function evidenceLabel(evidence: string): string | null {
  if (evidence === 'NEWLY_OBSERVED') return 'Newly observed'
  if (evidence === 'UNCONFIRMED_REMOVAL') return 'Removal unconfirmed'
  return null
}

export function uncertaintyCopy(subsystem: string): { title: string; body: string } {
  return UNCERTAINTY[subsystem] ?? {
    title: 'Discovery comparison incomplete',
    body: 'Some removals cannot be verified because a discovery subsystem was incomplete.',
  }
}

export function formatLocalTime(value: string, locale?: string, timeZone?: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return 'Unknown time'
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).format(date)
}

export function exactLocalTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'long' })
}

export function nodeMatches(change: NodeChange, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return true
  return [change.label, resourceTypeLabel(change.nodeType), ...change.fields.flatMap((field) => [fieldLabel(field.field), field.before ?? '', field.after ?? ''])]
    .some((value) => value.toLocaleLowerCase().includes(needle))
}

export function relationshipPresentations(payload: ChangesPayload): RelationshipPresentation[] {
  const resources = new Map(payload.resources.map((resource) => [resource.nodeId, resource]))
  const normal = payload.diff.relationshipChanges.filter((change) => change.evidence !== 'UNCONFIRMED_REMOVAL')
  const groups = new Map<string, RelationshipChange[]>()
  normal.forEach((change) => {
    const key = `${change.edge.source}\u0000${change.edge.relation}`
    groups.set(key, [...(groups.get(key) ?? []), change])
  })
  const presentations: RelationshipPresentation[] = []
  groups.forEach((records) => {
    const removed = records.filter((record) => record.kind === 'RELATIONSHIP_REMOVED')
    const added = records.filter((record) => record.kind === 'RELATIONSHIP_ADDED')
    if (records.length === 2 && removed.length === 1 && added.length === 1) {
      presentations.push(relationshipPresentation(records, resources))
    } else {
      records.forEach((record) => presentations.push(relationshipPresentation([record], resources)))
    }
  })
  return presentations.sort((left, right) => resourceTypeLabel(left.subject?.type ?? '').localeCompare(resourceTypeLabel(right.subject?.type ?? '')) || (left.subject?.label ?? '').localeCompare(right.subject?.label ?? '') || left.id.localeCompare(right.id))
}

export function uncertainRelationshipPresentations(payload: ChangesPayload): RelationshipPresentation[] {
  const resources = new Map(payload.resources.map((resource) => [resource.nodeId, resource]))
  return payload.diff.relationshipChanges
    .filter((change) => change.evidence === 'UNCONFIRMED_REMOVAL')
    .map((change) => relationshipPresentation([change], resources, true))
    .sort((left, right) => left.id.localeCompare(right.id))
}

export function filterAllows(filter: ChangeFilter, kind: string, uncertain = false): boolean {
  if (filter === 'ALL') return true
  if (filter === 'UNCERTAIN') return uncertain
  if (uncertain) return false
  if (filter === 'RELATIONSHIPS') return kind === 'RELATIONSHIP'
  return filter === kind
}

function relationshipPresentation(
  records: RelationshipChange[],
  resources: Map<string, ChangesPayload['resources'][number]>,
  uncertain = false,
): RelationshipPresentation {
  const first = records[0]
  const removed = records.find((record) => record.kind === 'RELATIONSHIP_REMOVED')
  const added = records.find((record) => record.kind === 'RELATIONSHIP_ADDED')
  const source = resources.get(first.edge.source)
  return {
    id: records.map((record) => record.id).sort().join('|'),
    relation: first.edge.relation,
    subject: source?.after ?? source?.before ?? null,
    subjectInCurrent: Boolean(source?.after),
    beforeTarget: removed ? resources.get(removed.edge.target)?.before ?? resources.get(removed.edge.target)?.after ?? null : null,
    afterTarget: added ? resources.get(added.edge.target)?.after ?? resources.get(added.edge.target)?.before ?? null : null,
    records: [...records].sort((left, right) => left.id.localeCompare(right.id)),
    uncertain,
  }
}

function humanize(value: string): string {
  const normalized = value.replaceAll('_', ' ').replaceAll('-', ' ').trim().toLocaleLowerCase()
  return normalized ? normalized[0].toLocaleUpperCase() + normalized.slice(1) : 'Resource'
}

const RESOURCE_TYPES: Record<string, string> = {
  SERVER: 'Server',
  REVERSE_PROXY: 'Reverse proxy',
  DOMAIN: 'Domain',
  UPSTREAM: 'Upstream',
  DOCKER_ENGINE: 'Docker engine',
  DOCKER_COMPOSE_PROJECT: 'Compose project',
  DOCKER_CONTAINER: 'Container',
  NETWORK: 'Network',
  MOUNT: 'Mount',
  DIRECTORY: 'Directory',
  PORT: 'Published port',
  SYSTEMD_SERVICE: 'Host service',
  HOST_LISTENER: 'Host listener',
}

const RELATIONS: Record<string, string> = {
  CONTAINS: 'Contains',
  ROUTES_TO: 'Routes to',
  ROUTES_THROUGH: 'Routes through',
  PROXIES_TO: 'Proxy target',
  RESOLVES_TO: 'Resolved target',
  RUNS: 'Runs',
  EXPOSES: 'Exposes',
  CONNECTED_TO: 'Connected to',
  MOUNTS: 'Mounts',
  LOCATED_IN: 'Located in',
  LISTENS_ON: 'Listens on',
  OWNED_BY: 'Owned by',
  TARGETS: 'Targets',
  PUBLISHED_AS: 'Published as',
}

const UNCERTAINTY: Record<string, { title: string; body: string }> = {
  SYSTEMD: { title: 'Systemd comparison incomplete', body: 'Some host-service removals cannot be verified because Systemd discovery was incomplete.' },
  HOST_LISTENERS: { title: 'Listener comparison incomplete', body: 'Some listener removals cannot be verified because listener discovery was incomplete.' },
  DOCKER: { title: 'Docker comparison incomplete', body: 'Some container-related removals cannot be verified because Docker discovery was incomplete.' },
  CADDY: { title: 'Caddy comparison incomplete', body: 'Some routing removals cannot be verified because Caddy discovery was incomplete.' },
}
