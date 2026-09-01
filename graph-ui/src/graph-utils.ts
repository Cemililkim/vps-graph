import type { Edge, Node } from '@xyflow/react'
import type { InfraEdge, InfraGraph, InfraNode, NodeType } from './types'
import { countLabel } from './resource-model.ts'

export type ViewMode = 'OVERVIEW' | 'NETWORKING' | 'STORAGE' | 'EVERYTHING'

export interface GraphUiState {
  collapsedComposeProjectIds?: ReadonlySet<string>
  focusedNodeId?: string | null
}

export interface VisibleGraph {
  nodes: InfraNode[]
  edges: InfraEdge[]
}

export interface SearchResult {
  node: InfraNode
  context: string
  rank: number
}

const viewTypes: Record<ViewMode, ReadonlySet<NodeType>> = {
  OVERVIEW: new Set(['SERVER', 'DOCKER_ENGINE', 'DOCKER_COMPOSE_PROJECT', 'DOCKER_CONTAINER']),
  NETWORKING: new Set(['SERVER', 'DOCKER_ENGINE', 'DOCKER_COMPOSE_PROJECT', 'DOCKER_CONTAINER', 'NETWORK', 'PORT']),
  STORAGE: new Set(['SERVER', 'DOCKER_ENGINE', 'DOCKER_COMPOSE_PROJECT', 'DOCKER_CONTAINER', 'MOUNT']),
  EVERYTHING: new Set(['SERVER', 'DOCKER_ENGINE', 'DOCKER_COMPOSE_PROJECT', 'DOCKER_CONTAINER', 'NETWORK', 'MOUNT', 'PORT', 'REVERSE_PROXY', 'DOMAIN', 'DIRECTORY']),
}

export function deriveVisibleGraph(graph: InfraGraph, view: ViewMode, state: GraphUiState = {}): VisibleGraph {
  const allowed = viewTypes[view]
  const collapsed = state.collapsedComposeProjectIds ?? new Set<string>()
  const collapsedContainers = new Set(
    graph.edges
      .filter((edge) => edge.relation === 'CONTAINS' && collapsed.has(edge.source))
      .map((edge) => edge.target),
  )
  const hidden = new Set(collapsedContainers)
  for (const edge of graph.edges) {
    if (collapsedContainers.has(edge.source) && (edge.relation === 'EXPOSES' || edge.relation === 'MOUNTS')) hidden.add(edge.target)
  }

  let nodes = graph.nodes.filter((node) => allowed.has(node.type) && !hidden.has(node.id))
  let nodeIds = new Set(nodes.map((node) => node.id))
  let edges = graph.edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
  nodes = nodes.filter((node) => !isOrphanedSecondary(node, edges))
  nodeIds = new Set(nodes.map((node) => node.id))
  edges = edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))

  if (state.focusedNodeId && nodeIds.has(state.focusedNodeId)) {
    const focusIds = new Set([state.focusedNodeId])
    graph.edges.forEach((edge) => {
      if (edge.source === state.focusedNodeId) focusIds.add(edge.target)
      if (edge.target === state.focusedNodeId) focusIds.add(edge.source)
    })
    nodes = nodes.filter((node) => focusIds.has(node.id))
    nodeIds = new Set(nodes.map((node) => node.id))
    edges = edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
  }
  return { nodes, edges }
}

function isOrphanedSecondary(node: InfraNode, edges: InfraEdge[]): boolean {
  return ['NETWORK', 'PORT', 'MOUNT'].includes(node.type) && !edges.some((edge) => edge.source === node.id || edge.target === node.id)
}

export function preferredView(node: InfraNode): ViewMode {
  if (node.type === 'NETWORK' || node.type === 'PORT') return 'NETWORKING'
  if (node.type === 'MOUNT') return 'STORAGE'
  return 'OVERVIEW'
}

export function searchGraph(graph: InfraGraph, query: string): SearchResult[] {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return []
  return graph.nodes
    .map((node) => ({ node, rank: searchRank(node, needle) }))
    .filter((result): result is { node: InfraNode; rank: number } => result.rank !== null)
    .sort((left, right) => left.rank - right.rank || left.node.label.localeCompare(right.node.label) || left.node.id.localeCompare(right.node.id))
    .slice(0, 12)
    .map((result) => ({ ...result, context: result.node.type.replaceAll('_', ' ') }))
}

function searchRank(node: InfraNode, needle: string): number | null {
  const label = node.label.toLocaleLowerCase()
  const primary = matchRank(label, needle)
  if (primary !== null) return primary
  const values = Object.values(node.metadata).map((value) => value.toLocaleLowerCase())
  const metadataRank = values.map((value) => matchRank(value, needle)).filter((rank): rank is number => rank !== null)
  return metadataRank.length ? 3 + Math.min(...metadataRank) : null
}

function matchRank(value: string, needle: string): number | null {
  if (value === needle) return 0
  if (value.startsWith(needle)) return 1
  if (value.includes(needle)) return 2
  return null
}

export function nodePresentation(node: InfraNode): { title: string; subtitle: string; badges: string[]; state?: string } {
  if (node.type === 'DOCKER_CONTAINER') {
    const ports = node.metadata.ports?.split(',').map((value) => value.trim()).filter(Boolean) ?? []
    const networks = countValues(node.metadata.networks)
    const mounts = countValues(node.metadata.mounts)
    return {
      title: node.label,
      subtitle: node.metadata['compose service'] ?? node.metadata.image ?? 'Container',
      state: node.metadata.state,
      badges: [ports[0], networks ? countLabel(networks, 'network') : '', mounts ? countLabel(mounts, 'mount') : ''].filter(Boolean),
    }
  }
  if (node.type === 'DOCKER_COMPOSE_PROJECT') return { title: node.label, subtitle: countLabel(Number(node.metadata['container count'] ?? 0), 'container'), badges: [] }
  if (node.type === 'NETWORK') return { title: node.label, subtitle: `${countLabel(countValues(node.metadata['connected containers']), 'connected container')}`, badges: [] }
  if (node.type === 'MOUNT') {
    const identity = node.metadata['volume name'] ?? pathTail(node.metadata.source) ?? pathTail(node.metadata.destination) ?? node.label
    return { title: identity, subtitle: `${node.metadata.type ?? 'mount'} · ${node.metadata.access ?? 'read-write'}`.toUpperCase(), badges: [] }
  }
  if (node.type === 'PORT') return { title: node.metadata['container port'] ? `${node.metadata['container port']}/${node.metadata.protocol}` : node.label, subtitle: node.metadata['host binding'] ?? 'INTERNAL', badges: [node.metadata.exposure ?? 'internal only'] }
  if (node.type === 'DOCKER_ENGINE') return { title: node.label, subtitle: node.metadata['discovery status'] ?? 'Docker', badges: [] }
  return { title: node.label, subtitle: node.type.replaceAll('_', ' '), badges: [] }
}

function countValues(value: string | undefined): number {
  return value ? value.split(',').filter(Boolean).length : 0
}

function pathTail(value: string | undefined): string | undefined {
  if (!value) return undefined
  const parts = value.split('/').filter(Boolean)
  return parts.at(-1) ?? value
}

export function layoutVisibleGraph(visible: VisibleGraph, view: ViewMode): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>()
  const sorted = (type: NodeType) => visible.nodes.filter((node) => node.type === type).sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id))
  const place = (nodes: InfraNode[], x: number, startY = 64, step = 142) => nodes.forEach((node, index) => positions.set(node.id, { x, y: startY + index * step }))
  const placeComposeGroups = (projectX: number, containerX: number) => {
    const projects = sorted('DOCKER_COMPOSE_PROJECT')
    const containers = sorted('DOCKER_CONTAINER')
    const projectContainers = new Map(projects.map((project) => [project.id, [] as InfraNode[]]))
    visible.edges.filter((edge) => edge.relation === 'CONTAINS').forEach((edge) => {
      const container = containers.find((node) => node.id === edge.target)
      if (container) projectContainers.get(edge.source)?.push(container)
    })
    let y = 64
    projects.forEach((project) => {
      const members = projectContainers.get(project.id)!.sort((left, right) => left.label.localeCompare(right.label) || left.id.localeCompare(right.id))
      positions.set(project.id, { x: projectX, y: y + Math.max(0, (members.length - 1) * 71) })
      members.forEach((container, index) => positions.set(container.id, { x: containerX, y: y + index * 142 }))
      y += Math.max(1, members.length) * 142 + 34
    })
    const grouped = new Set([...projectContainers.values()].flat().map((node) => node.id))
    place(containers.filter((node) => !grouped.has(node.id)), containerX, y)
  }

  if (view === 'NETWORKING') {
    place(sorted('SERVER'), 32, 310)
    place(sorted('DOCKER_ENGINE'), 245, 310)
    placeComposeGroups(470, 730)
    place(sorted('NETWORK'), 1000)
    place(sorted('PORT'), 1270)
    return positions
  }
  if (view === 'STORAGE') {
    place(sorted('SERVER'), 32, 310)
    place(sorted('DOCKER_ENGINE'), 245, 310)
    placeComposeGroups(470, 730)
    place(sorted('MOUNT'), 1010)
    return positions
  }
  place(sorted('SERVER'), 32, 310)
  place(sorted('DOCKER_ENGINE'), 270, 310)
  placeComposeGroups(530, 800)
  place(sorted('PORT'), 1070)
  place(sorted('MOUNT'), 1070, 64 + sorted('PORT').length * 142)
  place(sorted('NETWORK'), 1340)
  place(sorted('REVERSE_PROXY'), 530)
  place(sorted('DOMAIN'), 800)
  place(sorted('DIRECTORY'), 1070)
  return positions
}

export function toFlowGraph(visible: VisibleGraph, view: ViewMode, selectedNodeId: string | null): { nodes: Node<InfraNode & Record<string, unknown>, 'infrastructure'>[]; edges: Edge[] } {
  const positions = layoutVisibleGraph(visible, view)
  const related = new Set<string>()
  if (selectedNodeId) visible.edges.forEach((edge) => {
    if (edge.source === selectedNodeId) related.add(edge.target)
    if (edge.target === selectedNodeId) related.add(edge.source)
  })
  return {
    nodes: visible.nodes.map((node) => ({
      id: node.id,
      type: 'infrastructure',
      data: node as InfraNode & Record<string, unknown>,
      position: positions.get(node.id) ?? { x: 32, y: 32 },
      className: selectedNodeId && node.id !== selectedNodeId && !related.has(node.id) ? 'flow-node--subdued' : undefined,
    })),
    edges: visible.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      className: selectedNodeId && edge.source !== selectedNodeId && edge.target !== selectedNodeId ? 'flow-edge--subdued' : 'flow-edge--related',
    })),
  }
}
