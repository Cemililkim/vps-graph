import type { InfraEdge, InfraGraph, InfraNode, NodeType } from './types'

export type PrimarySection = 'OVERVIEW' | 'APPLICATIONS' | 'SERVICES' | 'ROUTING' | 'NETWORK' | 'STORAGE' | 'CHANGES'
export type NetworkSubsection = 'NETWORKS' | 'PORTS' | 'LISTENERS'
export type ServiceFilter = 'LISTENING' | 'ACTIVE' | 'ALL'

export interface ApplicationResource {
  project: InfraNode
  containers: InfraNode[]
  status: 'running' | 'stopped' | 'mixed' | 'unknown'
}

export interface RoutingResource {
  upstream: InfraNode
  proxy?: InfraNode
  domains: InfraNode[]
  target?: InfraNode
  hostListeners: InfraNode[]
  hostService?: InfraNode
}

export interface ResourceModel {
  byId: Map<string, InfraNode>
  host?: InfraNode
  engine?: InfraNode
  applications: ApplicationResource[]
  standaloneContainers: InfraNode[]
  containers: InfraNode[]
  networks: InfraNode[]
  ports: InfraNode[]
  mounts: InfraNode[]
  services: InfraNode[]
  listeners: InfraNode[]
  domains: InfraNode[]
  proxies: InfraNode[]
  upstreams: InfraNode[]
  routes: RoutingResource[]
  parentByChild: Map<string, string>
  edgesByNode: Map<string, InfraEdge[]>
}

const allowedMetadata: Partial<Record<NodeType, readonly string[]>> = {
  SERVER: ['hostname', 'ssh host', 'ssh username', 'operating system', 'os version', 'kernel', 'architecture', 'os', 'systemd discovery status', 'listener discovery status', 'systemd status reasons', 'listener status reasons'],
  DOCKER_ENGINE: ['discovery status', 'docker discovery state', 'docker version', 'caddy discovery status', 'caddy discovery state', 'role'],
  DOCKER_COMPOSE_PROJECT: ['project', 'working directory', 'config files', 'container count'],
  DOCKER_CONTAINER: ['container id', 'image', 'state', 'restart policy', 'compose project', 'compose service', 'ports', 'networks', 'mounts', 'status'],
  NETWORK: ['network', 'connected containers'],
  PORT: ['container port', 'protocol', 'host binding', 'exposure'],
  MOUNT: ['type', 'source', 'volume name', 'destination', 'access'],
  REVERSE_PROXY: ['proxy', 'container name', 'discovery status', 'route count', 'domain count', 'image'],
  DOMAIN: ['domain', 'route count', 'paths', 'resolution', 'upstreams', 'caddy instance', 'resolved containers', 'resolved host services'],
  UPSTREAM: ['dial', 'resolution', 'route id', 'backend set size', 'domains', 'paths', 'matching reason', 'shared network', 'container', 'compose project', 'compose service', 'container state', 'host resolution', 'target kind', 'matching port', 'host resolution reason', 'host service', 'eligible listeners', 'candidate services'],
  SYSTEMD_SERVICE: ['unit', 'description', 'load state', 'active state', 'sub state', 'unit file state', 'service type', 'user', 'group', 'control group', 'listener count', 'discovery status'],
  HOST_LISTENER: ['protocol', 'local address', 'port', 'bind', 'wildcard', 'loopback', 'ownership state', 'systemd unit', 'process name', 'discovery status'],
}

export function safeMetadata(node: InfraNode): Record<string, string> {
  const allowed = new Set(allowedMetadata[node.type] ?? [])
  return Object.fromEntries(Object.entries(node.metadata).filter(([key]) => allowed.has(key)))
}

export function resourceModel(graph: InfraGraph): ResourceModel {
  const byId = new Map(graph.nodes.map((node) => [node.id, node]))
  const containers = graph.nodes.filter((node) => node.type === 'DOCKER_CONTAINER').sort(byLabel)
  const parentByChild = new Map<string, string>()
  const edgesByNode = new Map<string, InfraEdge[]>()
  graph.edges.forEach((edge) => {
    if (['CONTAINS', 'RUNS', 'EXPOSES', 'MOUNTS'].includes(edge.relation)) parentByChild.set(edge.target, edge.source)
    edgesByNode.set(edge.source, [...(edgesByNode.get(edge.source) ?? []), edge])
    edgesByNode.set(edge.target, [...(edgesByNode.get(edge.target) ?? []), edge])
  })
  const projects = graph.nodes.filter((node) => node.type === 'DOCKER_COMPOSE_PROJECT').sort(byLabel)
  const applications = projects.map((project) => {
    const members = containers.filter((container) => parentByChild.get(container.id) === project.id)
    return { project, containers: members, status: aggregateStatus(members) }
  })
  const grouped = new Set(applications.flatMap((application) => application.containers.map((container) => container.id)))
  const domains = graph.nodes.filter((node) => node.type === 'DOMAIN').sort(byLabel)
  const proxies = graph.nodes.filter((node) => node.type === 'REVERSE_PROXY').sort(byLabel)
  const upstreams = graph.nodes.filter((node) => node.type === 'UPSTREAM').sort(byLabel)
  const services = graph.nodes.filter((node) => node.type === 'SYSTEMD_SERVICE').sort(byLabel)
  const listeners = graph.nodes.filter((node) => node.type === 'HOST_LISTENER').sort(byLabel)
  const routes = upstreams.map((upstream) => {
    const related = edgesByNode.get(upstream.id) ?? []
    const nodeFor = (id: string) => byId.get(id)
    const proxy = related.find((edge) => edge.relation === 'PROXIES_TO' && edge.target === upstream.id)?.source
    const target = related.find((edge) => edge.relation === 'RESOLVES_TO' && edge.source === upstream.id)?.target
    const hostListeners = related.filter((edge) => edge.relation === 'TARGETS' && edge.source === upstream.id).map((edge) => nodeFor(edge.target)).filter((node): node is InfraNode => node?.type === 'HOST_LISTENER').sort(byLabel)
    const owners = [...new Map(hostListeners.flatMap((listener) => (edgesByNode.get(listener.id) ?? [])
      .filter((edge) => edge.relation === 'OWNED_BY' && edge.source === listener.id)
      .map((edge) => nodeFor(edge.target)))
      .filter((node): node is InfraNode => node?.type === 'SYSTEMD_SERVICE')
      .map((node) => [node.id, node])).values()]
    const routeDomains = related.filter((edge) => edge.relation === 'ROUTES_TO' && edge.target === upstream.id).map((edge) => nodeFor(edge.source)).filter((node): node is InfraNode => node?.type === 'DOMAIN').sort(byLabel)
    return { upstream, proxy: proxy ? nodeFor(proxy) : undefined, domains: routeDomains, target: target ? nodeFor(target) : undefined, hostListeners, hostService: owners.length === 1 ? owners[0] : undefined }
  }).sort((left, right) => (left.domains[0]?.label ?? '').localeCompare(right.domains[0]?.label ?? '') || left.upstream.id.localeCompare(right.upstream.id))
  return {
    byId,
    host: graph.nodes.find((node) => node.type === 'SERVER'),
    engine: graph.nodes.find((node) => node.type === 'DOCKER_ENGINE'),
    applications,
    standaloneContainers: containers.filter((container) => !grouped.has(container.id)),
    containers,
    networks: graph.nodes.filter((node) => node.type === 'NETWORK').sort(byLabel),
    ports: graph.nodes.filter((node) => node.type === 'PORT').sort(byLabel),
    mounts: graph.nodes.filter((node) => node.type === 'MOUNT').sort(byLabel),
    services,
    listeners,
    domains,
    proxies,
    upstreams,
    routes,
    parentByChild,
    edgesByNode,
  }
}

export function pageFor(node: InfraNode): PrimarySection {
  if (node.type === 'DOMAIN' || node.type === 'REVERSE_PROXY' || node.type === 'UPSTREAM') return 'ROUTING'
  if (node.type === 'NETWORK' || node.type === 'PORT') return 'NETWORK'
  if (node.type === 'HOST_LISTENER') return 'NETWORK'
  if (node.type === 'SYSTEMD_SERVICE') return 'SERVICES'
  if (node.type === 'MOUNT') return 'STORAGE'
  if (node.type === 'DOCKER_COMPOSE_PROJECT' || node.type === 'DOCKER_CONTAINER') return 'APPLICATIONS'
  return 'OVERVIEW'
}

export function networkSubsectionFor(node: InfraNode): NetworkSubsection | null {
  if (node.type === 'NETWORK') return 'NETWORKS'
  if (node.type === 'PORT') return 'PORTS'
  if (node.type === 'HOST_LISTENER') return 'LISTENERS'
  return null
}

export function resourceContext(node: InfraNode, model: ResourceModel): string {
  const parent = model.parentByChild.get(node.id)
  const parentNode = parent ? model.byId.get(parent) : undefined
  if (node.type === 'DOCKER_CONTAINER') return parentNode?.label ?? 'Standalone container'
  if (node.type === 'PORT' || node.type === 'MOUNT') {
    const owner = parent ? model.byId.get(parent) : undefined
    return owner?.label ?? (node.type === 'PORT' ? 'Container port' : 'Container mount')
  }
  if (node.type === 'NETWORK') return countLabel(connectedContainers(node, model).length, 'container')
  if (node.type === 'SYSTEMD_SERVICE') return `${safeMetadata(node)['active state'] ?? 'unknown'} · ${countLabel(Number(safeMetadata(node)['listener count'] ?? 0), 'listener')}`
  if (node.type === 'HOST_LISTENER') return safeMetadata(node)['systemd unit'] ?? safeMetadata(node)['ownership state']?.toLowerCase() ?? 'unresolved owner'
  if (node.type === 'DOCKER_COMPOSE_PROJECT') return countLabel(model.applications.find((item) => item.project.id === node.id)?.containers.length ?? 0, 'container')
  if (node.type === 'DOMAIN') return `${safeMetadata(node).resolution?.toLowerCase() ?? 'unresolved'} route`
  if (node.type === 'UPSTREAM') {
    const domains = relatedResources(node, model, 'ROUTES_TO').filter((candidate) => candidate.type === 'DOMAIN').map((candidate) => candidate.label)
    return safeMetadata(node).domains ?? (domains.length ? domains.join(', ') : safeMetadata(node).resolution?.toLowerCase() ?? 'unresolved')
  }
  if (node.type === 'REVERSE_PROXY') return countLabel(Number(safeMetadata(node)['route count'] ?? 0), 'route')
  return node.type.replaceAll('_', ' ')
}

export function countLabel(count: number, singular: string): string {
  return `${count} ${singular}${count === 1 ? '' : 's'}`
}

export function searchResources(graph: InfraGraph, query: string): InfraNode[] {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return []
  return graph.nodes
    .map((node) => ({ node, rank: searchRank(node, needle) }))
    .filter((result): result is { node: InfraNode; rank: number } => result.rank !== null)
    .sort((left, right) => left.rank - right.rank || left.node.label.localeCompare(right.node.label) || left.node.id.localeCompare(right.node.id))
    .slice(0, 12)
    .map((result) => result.node)
}

export function connectedContainers(network: InfraNode, model: ResourceModel): InfraNode[] {
  return (model.edgesByNode.get(network.id) ?? [])
    .filter((edge) => edge.relation === 'CONNECTED_TO')
    .map((edge) => model.byId.get(edge.source === network.id ? edge.target : edge.source))
    .filter((node): node is InfraNode => node?.type === 'DOCKER_CONTAINER')
    .sort(byLabel)
}

export function relatedResources(node: InfraNode, model: ResourceModel, relation: InfraEdge['relation']): InfraNode[] {
  return (model.edgesByNode.get(node.id) ?? [])
    .filter((edge) => edge.relation === relation)
    .map((edge) => model.byId.get(edge.source === node.id ? edge.target : edge.source))
    .filter((candidate): candidate is InfraNode => Boolean(candidate))
    .sort(byLabel)
}

export function listeningServices(model: ResourceModel): InfraNode[] {
  return model.services.filter((service) => Number(safeMetadata(service)['listener count'] ?? 0) > 0)
}

export function servicesForFilter(model: ResourceModel, filter: ServiceFilter): InfraNode[] {
  if (filter === 'LISTENING') return listeningServices(model)
  if (filter === 'ACTIVE') return model.services.filter((service) => safeMetadata(service)['active state'] === 'active')
  return model.services
}

export function publishedPorts(listener: InfraNode, model: ResourceModel): InfraNode[] {
  return relatedResources(listener, model, 'PUBLISHED_AS').filter((node) => node.type === 'PORT')
}

export function routedDomains(container: InfraNode, model: ResourceModel): InfraNode[] {
  const upstreamIds = (model.edgesByNode.get(container.id) ?? [])
    .filter((edge) => edge.relation === 'RESOLVES_TO' && edge.target === container.id)
    .map((edge) => edge.source)
  return [...new Map(upstreamIds.flatMap((id) => (model.edgesByNode.get(id) ?? []))
    .filter((edge) => edge.relation === 'ROUTES_TO' && upstreamIds.includes(edge.target))
    .map((edge) => model.byId.get(edge.source))
    .filter((node): node is InfraNode => node?.type === 'DOMAIN')
    .map((node) => [node.id, node])).values()].sort(byLabel)
}

export function compactPath(value: string | undefined): string {
  if (!value) return 'Not available'
  const parts = value.split(/[\\/]/).filter(Boolean)
  return parts.at(-1) ?? value
}

export function containerState(node: InfraNode): string {
  return safeMetadata(node).state ?? safeMetadata(node).status ?? 'unknown'
}

function aggregateStatus(containers: InfraNode[]): ApplicationResource['status'] {
  if (!containers.length) return 'unknown'
  const states = new Set(containers.map(containerState))
  if (states.size === 1 && states.has('running')) return 'running'
  if (states.size === 1) return 'stopped'
  return 'mixed'
}

function searchRank(node: InfraNode, needle: string): number | null {
  const label = matchRank(node.label.toLocaleLowerCase(), needle)
  if (label !== null) return label
  const ranks = Object.entries(safeMetadata(node)).filter(([key]) => key !== 'control group' && !key.endsWith('status reasons')).map(([, value]) => matchRank(value.toLocaleLowerCase(), needle)).filter((rank): rank is number => rank !== null)
  return ranks.length ? 3 + Math.min(...ranks) : null
}

function matchRank(value: string, needle: string): number | null {
  if (value === needle) return 0
  if (value.startsWith(needle)) return 1
  return value.includes(needle) ? 2 : null
}

function byLabel(left: InfraNode, right: InfraNode): number {
  return left.label.localeCompare(right.label) || left.id.localeCompare(right.id)
}
