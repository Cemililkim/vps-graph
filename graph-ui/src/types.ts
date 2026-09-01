export type NodeType =
  | 'SERVER'
  | 'REVERSE_PROXY'
  | 'DOMAIN'
  | 'UPSTREAM'
  | 'DOCKER_ENGINE'
  | 'DOCKER_COMPOSE_PROJECT'
  | 'DOCKER_CONTAINER'
  | 'NETWORK'
  | 'MOUNT'
  | 'DIRECTORY'
  | 'PORT'
  | 'SYSTEMD_SERVICE'
  | 'HOST_LISTENER'

export type RelationType = 'CONTAINS' | 'ROUTES_TO' | 'ROUTES_THROUGH' | 'PROXIES_TO' | 'RESOLVES_TO' | 'RUNS' | 'EXPOSES' | 'CONNECTED_TO' | 'MOUNTS' | 'LOCATED_IN' | 'LISTENS_ON' | 'OWNED_BY' | 'TARGETS' | 'PUBLISHED_AS'

export interface InfraNode {
  id: string
  label: string
  type: NodeType
  metadata: Record<string, string>
}

export interface InfraEdge {
  id: string
  source: string
  target: string
  relation: RelationType
}

export interface InfraGraph {
  nodes: InfraNode[]
  edges: InfraEdge[]
}

export interface ScanRequest {
  host: string
  port: number
  username: string
  privateKeyPath: string
  rememberConnection: boolean
}

export interface ScanAcknowledgement {
  accepted: boolean
  errorCode?: string
  errorMessage?: string
}

export interface ScanStatusEvent {
  state: 'CONNECTING' | 'SCANNING' | 'CONNECTED' | 'ERROR'
  graph?: InfraGraph
  changes?: ChangesResponse
  errorCode?: string
  errorMessage?: string
}

export interface ChangesResponse {
  ok: boolean
  payload?: ChangesPayload | null
  error?: { code: string; message: string } | null
}

export interface ChangesPayload {
  schemaVersion: number
  comparison: {
    previous?: SnapshotDisplayMetadata | null
    current: SnapshotDisplayMetadata
  }
  diff: InfrastructureDiff
  resources: ChangeResourceSnapshot[]
  history: SnapshotHistoryItem[]
}

export interface SnapshotDisplayMetadata {
  snapshotId: string
  capturedAt: string
  fingerprint: string
  persisted: boolean
}

export interface SnapshotHistoryItem {
  snapshotId: string
  capturedAt: string
  fingerprint: string
}

export interface SnapshotNode {
  id: string
  label: string
  type: string
  metadata: Record<string, string>
}

export interface ChangeResourceSnapshot {
  nodeId: string
  before?: SnapshotNode | null
  after?: SnapshotNode | null
}

export interface InfrastructureDiff {
  previousSnapshotId?: string | null
  currentSnapshotId: string
  previousCapturedAt?: string | null
  currentCapturedAt: string
  status: string
  summary: DiffSummary
  nodeChanges: NodeChange[]
  relationshipChanges: RelationshipChange[]
  uncertainties: DiffUncertainty[]
}

export interface DiffSummary {
  addedNodes: number
  removedNodes: number
  modifiedNodes: number
  addedRelationships: number
  removedRelationships: number
  uncertainComparisons: number
  hasChanges: boolean
  isComplete: boolean
}

export interface FieldChange {
  field: string
  before?: string | null
  after?: string | null
}

export interface NodeChange {
  id: string
  kind: string
  evidence: string
  nodeId: string
  nodeType: string
  label: string
  fields: FieldChange[]
}

export interface RelationshipChange {
  id: string
  kind: string
  evidence: string
  edge: { id: string; source: string; target: string; relation: string }
}

export interface DiffUncertainty {
  subsystem: string
  reason: string
  affectedResourceTypes: string[]
}

declare global {
  interface Window {
    vpsGraph?: {
      requestGraph: () => Promise<string>
      requestConnectionPreferences: () => Promise<string>
      requestChanges: () => Promise<string>
      compareSnapshots: (previousSnapshotId: string, currentSnapshotId: string) => Promise<string>
      scanHost: (target: ScanRequest) => Promise<string>
      choosePrivateKey: () => Promise<string>
    }
  }
}
