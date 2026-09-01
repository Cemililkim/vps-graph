import type { ChangesPayload, SnapshotNode } from '../../src/types.ts'

const node = (id: string, label: string, type: string, metadata: Record<string, string> = {}): SnapshotNode => ({ id, label, type, metadata })

const previous = {
  n8n: node('container:n8n', 'n8n', 'DOCKER_CONTAINER', { image: 'n8nio/n8n:1.2', state: 'running' }),
  oldWorker: node('container:old-worker', 'old-worker', 'DOCKER_CONTAINER', { image: 'worker:1.3', state: 'running', 'compose project': 'app' }),
  apiDomain: node('domain:api', 'api.example.com', 'DOMAIN'),
  apiOld: node('container:portfolio-api', 'portfolio-api:4000', 'DOCKER_CONTAINER'),
  monitor: node('domain:monitor', 'monitor.example.net', 'DOMAIN'),
  netdata: node('service:netdata', 'netdata.service', 'SYSTEMD_SERVICE', { unit: 'netdata.service', 'active state': 'active' }),
  escaped: node('service:escaped', 'systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service', 'SYSTEMD_SERVICE'),
  scoped: node('listener:scoped', '[fe80::eac9:da0a:d32:2190%eth0]:19999/tcp', 'HOST_LISTENER', { 'local address': 'fe80::eac9:da0a:d32:2190%eth0' }),
}

const current = {
  n8n: node('container:n8n', 'n8n', 'DOCKER_CONTAINER', { image: 'n8nio/n8n:1.3-with-a-very-long-but-safe-image-reference', state: 'stopped' }),
  worker: node('container:worker', 'worker', 'DOCKER_CONTAINER', { image: 'worker:2.0', state: 'running' }),
  workerService: node('service:worker', 'worker.service', 'SYSTEMD_SERVICE', { unit: 'worker.service', 'active state': 'active' }),
  apiDomain: previous.apiDomain,
  apiNew: node('container:api-v2', 'api-v2:4000', 'DOCKER_CONTAINER'),
  monitor: previous.monitor,
  metrics: node('service:metrics', 'metrics.service', 'SYSTEMD_SERVICE', { unit: 'metrics.service', 'active state': 'active' }),
}

export const changedPayload: ChangesPayload = {
  schemaVersion: 1,
  comparison: {
    previous: { snapshotId: '11111111-1111-4111-8111-111111111111', capturedAt: '2026-08-30T08:05:00Z', fingerprint: '1111111111', persisted: true },
    current: { snapshotId: '22222222-2222-4222-8222-222222222222', capturedAt: '2026-08-30T11:42:00Z', fingerprint: '2222222222', persisted: true },
  },
  diff: {
    previousSnapshotId: '11111111-1111-4111-8111-111111111111',
    currentSnapshotId: '22222222-2222-4222-8222-222222222222',
    previousCapturedAt: '2026-08-30T08:05:00Z',
    currentCapturedAt: '2026-08-30T11:42:00Z',
    status: 'PARTIAL',
    summary: { addedNodes: 2, removedNodes: 1, modifiedNodes: 1, addedRelationships: 2, removedRelationships: 2, uncertainComparisons: 3, hasChanges: true, isComplete: false },
    nodeChanges: [
      { id: 'node:MODIFIED:container:n8n', kind: 'MODIFIED', evidence: 'CONFIRMED', nodeId: current.n8n.id, nodeType: current.n8n.type, label: current.n8n.label, fields: [{ field: 'metadata:state', before: 'running', after: 'stopped' }, { field: 'metadata:image', before: previous.n8n.metadata.image, after: current.n8n.metadata.image }] },
      { id: 'node:ADDED:container:worker', kind: 'ADDED', evidence: 'CONFIRMED', nodeId: current.worker.id, nodeType: current.worker.type, label: current.worker.label, fields: [] },
      { id: 'node:ADDED:service:worker', kind: 'ADDED', evidence: 'NEWLY_OBSERVED', nodeId: current.workerService.id, nodeType: current.workerService.type, label: current.workerService.label, fields: [] },
      { id: 'node:REMOVED:container:old-worker', kind: 'REMOVED', evidence: 'CONFIRMED', nodeId: previous.oldWorker.id, nodeType: previous.oldWorker.type, label: previous.oldWorker.label, fields: [] },
      { id: 'node:REMOVED:service:escaped', kind: 'REMOVED', evidence: 'UNCONFIRMED_REMOVAL', nodeId: previous.escaped.id, nodeType: previous.escaped.type, label: previous.escaped.label, fields: [] },
      { id: 'node:REMOVED:listener:scoped', kind: 'REMOVED', evidence: 'UNCONFIRMED_REMOVAL', nodeId: previous.scoped.id, nodeType: previous.scoped.type, label: previous.scoped.label, fields: [] },
    ],
    relationshipChanges: [
      { id: 'relationship:REMOVED:api-old', kind: 'RELATIONSHIP_REMOVED', evidence: 'CONFIRMED', edge: { id: 'api-old', source: previous.apiDomain.id, target: previous.apiOld.id, relation: 'RESOLVES_TO' } },
      { id: 'relationship:ADDED:api-new', kind: 'RELATIONSHIP_ADDED', evidence: 'CONFIRMED', edge: { id: 'api-new', source: current.apiDomain.id, target: current.apiNew.id, relation: 'RESOLVES_TO' } },
      { id: 'relationship:REMOVED:monitor-old', kind: 'RELATIONSHIP_REMOVED', evidence: 'CONFIRMED', edge: { id: 'monitor-old', source: previous.monitor.id, target: previous.netdata.id, relation: 'RESOLVES_TO' } },
      { id: 'relationship:ADDED:monitor-new', kind: 'RELATIONSHIP_ADDED', evidence: 'CONFIRMED', edge: { id: 'monitor-new', source: current.monitor.id, target: current.metrics.id, relation: 'RESOLVES_TO' } },
    ],
    uncertainties: [{ subsystem: 'SYSTEMD', reason: 'CURRENT_DISCOVERY_INCOMPLETE', affectedResourceTypes: ['SYSTEMD_SERVICE'] }],
  },
  resources: [
    { nodeId: current.n8n.id, before: previous.n8n, after: current.n8n },
    { nodeId: current.worker.id, after: current.worker },
    { nodeId: current.workerService.id, after: current.workerService },
    { nodeId: previous.oldWorker.id, before: previous.oldWorker },
    { nodeId: previous.escaped.id, before: previous.escaped },
    { nodeId: previous.scoped.id, before: previous.scoped },
    { nodeId: previous.apiDomain.id, before: previous.apiDomain, after: current.apiDomain },
    { nodeId: previous.apiOld.id, before: previous.apiOld },
    { nodeId: current.apiNew.id, after: current.apiNew },
    { nodeId: previous.monitor.id, before: previous.monitor, after: current.monitor },
    { nodeId: previous.netdata.id, before: previous.netdata },
    { nodeId: current.metrics.id, after: current.metrics },
  ],
  history: [
    { snapshotId: '22222222-2222-4222-8222-222222222222', capturedAt: '2026-08-30T11:42:00Z', fingerprint: '2222222222' },
    { snapshotId: '11111111-1111-4111-8111-111111111111', capturedAt: '2026-08-30T08:05:00Z', fingerprint: '1111111111' },
    { snapshotId: '00000000-0000-4000-8000-000000000000', capturedAt: '2026-08-29T19:45:00Z', fingerprint: '0000000000' },
  ],
}

export const baselinePayload: ChangesPayload = {
  ...changedPayload,
  comparison: { current: changedPayload.comparison.current },
  diff: { ...changedPayload.diff, previousSnapshotId: null, previousCapturedAt: null, status: 'NO_BASELINE', summary: { addedNodes: 0, removedNodes: 0, modifiedNodes: 0, addedRelationships: 0, removedRelationships: 0, uncertainComparisons: 0, hasChanges: false, isComplete: false }, nodeChanges: [], relationshipChanges: [], uncertainties: [] },
  resources: [],
  history: [changedPayload.history[0]],
}

export const noChangesPayload: ChangesPayload = {
  ...changedPayload,
  diff: { ...changedPayload.diff, status: 'NO_CHANGES', summary: { addedNodes: 0, removedNodes: 0, modifiedNodes: 0, addedRelationships: 0, removedRelationships: 0, uncertainComparisons: 0, hasChanges: false, isComplete: true }, nodeChanges: [], relationshipChanges: [], uncertainties: [] },
  resources: [],
}
