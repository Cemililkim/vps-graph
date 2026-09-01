import assert from 'node:assert/strict'
import test from 'node:test'
import { compactPath, connectedContainers, countLabel, listeningServices, networkSubsectionFor, pageFor, publishedPorts, relatedResources, resourceContext, resourceModel, routedDomains, safeMetadata, searchResources, servicesForFilter } from '../src/resource-model.ts'
import { busyVpsGraph } from './fixtures/busy-vps.ts'
import type { InfraGraph } from '../src/types.ts'

const routingGraph: InfraGraph = {
  nodes: [
    { id: 'server', label: 'vps', type: 'SERVER', metadata: {} },
    { id: 'docker', label: 'Docker', type: 'DOCKER_ENGINE', metadata: { 'caddy discovery status': 'Caddy routes discovered' } },
    { id: 'container', label: 'portfolio-web', type: 'DOCKER_CONTAINER', metadata: { state: 'running', DATABASE_URL: 'super-secret' } },
    { id: 'proxy', label: 'caddy', type: 'REVERSE_PROXY', metadata: { proxy: 'Caddy', 'route count': '2', ignored: 'secret-label' } },
    { id: 'domain-a', label: 'example.com', type: 'DOMAIN', metadata: { domain: 'example.com', resolution: 'RESOLVED', upstreams: 'portfolio-web:3000', rawConfig: 'super-secret' } },
    { id: 'domain-b', label: 'www.example.com', type: 'DOMAIN', metadata: { domain: 'www.example.com', resolution: 'RESOLVED' } },
    { id: 'upstream', label: 'portfolio-web:3000', type: 'UPSTREAM', metadata: { dial: 'portfolio-web:3000', resolution: 'RESOLVED', container: 'portfolio-web', 'shared network': 'web', rawLabels: 'super-secret' } },
    { id: 'upstream-admin', label: 'portfolio-web:3000', type: 'UPSTREAM', metadata: { dial: 'portfolio-web:3000', resolution: 'RESOLVED', domains: 'admin.example.com', 'route id': 'admin-route' } },
    { id: 'host-upstream', label: 'host.docker.internal:19999', type: 'UPSTREAM', metadata: { dial: 'host.docker.internal:19999', resolution: 'HOST_TARGET' } },
  ],
  edges: [
    { id: 'contains', source: 'docker', target: 'container', relation: 'RUNS' },
    { id: 'proxy-upstream', source: 'proxy', target: 'upstream', relation: 'PROXIES_TO' },
    { id: 'proxy-upstream-admin', source: 'proxy', target: 'upstream-admin', relation: 'PROXIES_TO' },
    { id: 'domain-a-upstream', source: 'domain-a', target: 'upstream', relation: 'ROUTES_TO' },
    { id: 'domain-b-upstream', source: 'domain-b', target: 'upstream', relation: 'ROUTES_TO' },
    { id: 'resolves', source: 'upstream', target: 'container', relation: 'RESOLVES_TO' },
    { id: 'admin-upstream', source: 'domain-a', target: 'upstream-admin', relation: 'ROUTES_TO' },
    { id: 'proxy-host', source: 'proxy', target: 'host-upstream', relation: 'PROXIES_TO' },
  ],
}

const hostServiceGraph: InfraGraph = {
  nodes: [
    { id: 'server', label: 'vps', type: 'SERVER', metadata: { 'systemd discovery status': 'DISCOVERED', 'listener discovery status': 'DISCOVERED' } },
    { id: 'service:netdata', label: 'netdata.service', type: 'SYSTEMD_SERVICE', metadata: { unit: 'netdata.service', description: 'Real-time monitoring', 'active state': 'active', 'sub state': 'running', 'listener count': '2', 'control group': '/system.slice/netdata.service', secret: 'do-not-index' } },
    { id: 'service:ssh', label: 'ssh.service', type: 'SYSTEMD_SERVICE', metadata: { unit: 'ssh.service', 'active state': 'active', 'listener count': '0' } },
    { id: 'listener:loopback', label: '127.0.0.1:19999/tcp', type: 'HOST_LISTENER', metadata: { bind: '127.0.0.1:19999/tcp', protocol: 'tcp', 'local address': '127.0.0.1', port: '19999', loopback: 'true', 'ownership state': 'SYSTEMD_SERVICE', 'systemd unit': 'netdata.service' } },
    { id: 'listener:bridge', label: '172.17.0.1:19999/tcp', type: 'HOST_LISTENER', metadata: { bind: '172.17.0.1:19999/tcp', protocol: 'tcp', 'local address': '172.17.0.1', port: '19999', loopback: 'false', 'ownership state': 'SYSTEMD_SERVICE', 'systemd unit': 'netdata.service' } },
    { id: 'port:published', label: '19999/tcp → 172.17.0.1:19999', type: 'PORT', metadata: { 'container port': '19999', protocol: 'tcp', 'host binding': '172.17.0.1:19999', exposure: 'published' } },
    { id: 'upstream:host', label: 'host.docker.internal:19999', type: 'UPSTREAM', metadata: { dial: 'host.docker.internal:19999', resolution: 'HOST_TARGET', 'host resolution': 'HOST_SERVICE', 'host service': 'netdata.service', 'host resolution reason': 'Non-loopback TCP listeners share one Systemd owner.' } },
    { id: 'domain:monitor', label: 'monitor.example', type: 'DOMAIN', metadata: { domain: 'monitor.example', resolution: 'HOST_SERVICE', 'resolved host services': 'netdata.service' } },
  ],
  edges: [
    { id: 'runs-netdata', source: 'server', target: 'service:netdata', relation: 'RUNS' },
    { id: 'runs-ssh', source: 'server', target: 'service:ssh', relation: 'RUNS' },
    { id: 'owns-loopback', source: 'listener:loopback', target: 'service:netdata', relation: 'OWNED_BY' },
    { id: 'owns-bridge', source: 'listener:bridge', target: 'service:netdata', relation: 'OWNED_BY' },
    { id: 'targets-bridge', source: 'upstream:host', target: 'listener:bridge', relation: 'TARGETS' },
    { id: 'published-as', source: 'port:published', target: 'listener:bridge', relation: 'PUBLISHED_AS' },
    { id: 'route-host', source: 'domain:monitor', target: 'upstream:host', relation: 'ROUTES_TO' },
  ],
}

test('overview and explorer projections derive real counts and Compose hierarchy', () => {
  const model = resourceModel(busyVpsGraph)
  assert.equal(model.host?.label, 'busy-vps')
  assert.equal(model.applications.length, 10)
  assert.equal(model.containers.length, 40)
  assert.equal(model.standaloneContainers.length, 0)
  assert.deepEqual(model.applications[0].containers.map((node) => node.label), ['project-1-service-1', 'project-1-service-2', 'project-1-service-3', 'project-1-service-4'])
})

test('standalone containers remain available outside Compose projects', () => {
  const graph = structuredClone(busyVpsGraph)
  graph.nodes.push({ id: 'standalone:watcher', label: 'watcher', type: 'DOCKER_CONTAINER', metadata: { state: 'running' } })
  graph.edges.push({ id: 'docker-runs-watcher', source: 'docker:busy', target: 'standalone:watcher', relation: 'RUNS' })
  const model = resourceModel(graph)
  assert.deepEqual(model.standaloneContainers.map((node) => node.label), ['watcher'])
})

test('network and storage projections keep relationships deterministic', () => {
  const model = resourceModel(busyVpsGraph)
  const web = model.networks.find((node) => node.label === 'web')!
  assert.equal(connectedContainers(web, model).length, 40)
  const container = model.containers[0]
  const mount = relatedResources(container, model, 'MOUNTS')[0]
  assert.equal(safeMetadata(mount).access, 'read-write')
  assert.equal(compactPath(safeMetadata(mount).source), 'data-1')
  assert.equal(safeMetadata(mount).source, '/srv/project-1/data-1')
})

test('resource search ranks safe known metadata and routes results to product pages', () => {
  const model = resourceModel(busyVpsGraph)
  assert.equal(searchResources(busyVpsGraph, 'project-2-service-1')[0].label, 'project-2-service-1')
  assert.equal(searchResources(busyVpsGraph, 'image-3')[0].type, 'DOCKER_CONTAINER')
  assert.equal(searchResources(busyVpsGraph, 'project-2')[0].type, 'DOCKER_COMPOSE_PROJECT')
  assert.equal(searchResources(busyVpsGraph, 'web')[0].type, 'NETWORK')
  assert.equal(searchResources(busyVpsGraph, '3001')[0].type, 'PORT')
  assert.equal(searchResources(busyVpsGraph, '/srv/project-1/data-1')[0].type, 'MOUNT')
  assert.equal(pageFor(model.networks[0]), 'NETWORK')
  assert.equal(pageFor(model.ports[0]), 'NETWORK')
  assert.equal(networkSubsectionFor(model.networks[0]), 'NETWORKS')
  assert.equal(networkSubsectionFor(model.ports[0]), 'PORTS')
  assert.equal(networkSubsectionFor(model.containers[0]), null)
  assert.equal(pageFor(model.mounts[0]), 'STORAGE')
  assert.equal(resourceContext(model.networks[0], model), '40 containers')
})

test('unknown and sensitive metadata cannot enter search or page projections', () => {
  const graph = structuredClone(busyVpsGraph)
  graph.nodes.find((node) => node.type === 'DOCKER_CONTAINER')!.metadata.DATABASE_URL = 'postgresql://super-secret'
  const container = graph.nodes.find((node) => node.type === 'DOCKER_CONTAINER')!
  assert.equal(safeMetadata(container).DATABASE_URL, undefined)
  assert.equal(searchResources(graph, 'super-secret').length, 0)
  assert.equal(searchResources(graph, 'DATABASE_URL').length, 0)
})

test('routing projection groups domains around canonical upstream and container resources', () => {
  const model = resourceModel(routingGraph)
  assert.equal(model.routes.length, 3)
  const resolved = model.routes.find((route) => route.upstream.id === 'upstream')!
  assert.deepEqual(resolved.domains.map((node) => node.label), ['example.com', 'www.example.com'])
  assert.equal(resolved.proxy?.id, 'proxy')
  assert.equal(resolved.target?.id, 'container')
  assert.deepEqual(routedDomains(model.byId.get('container')!, model).map((node) => node.label), ['example.com', 'www.example.com'])
  assert.equal(model.parentByChild.get('container'), 'docker')
  assert.equal(pageFor(model.domains[0]), 'ROUTING')
  assert.equal(pageFor(model.upstreams[0]), 'ROUTING')
})

test('routing search exposes only allowlisted sanitized metadata', () => {
  assert.equal(searchResources(routingGraph, 'example.com')[0].type, 'DOMAIN')
  assert.equal(searchResources(routingGraph, 'portfolio-web:3000')[0].type, 'UPSTREAM')
  assert.equal(searchResources(routingGraph, 'host.docker.internal')[0].type, 'UPSTREAM')
  assert.equal(safeMetadata(routingGraph.nodes.find((node) => node.id === 'upstream')!).rawLabels, undefined)
  assert.equal(searchResources(routingGraph, 'super-secret').length, 0)
  assert.equal(searchResources(routingGraph, 'secret-label').length, 0)
})

test('route-specific upstreams sharing a dial stay separate and searchable with context', () => {
  const model = resourceModel(routingGraph)
  const results = searchResources(routingGraph, 'portfolio-web:3000').filter((node) => node.type === 'UPSTREAM')
  assert.deepEqual(results.map((node) => node.id).sort(), ['upstream', 'upstream-admin'])
  assert.equal(resourceContext(results.find((node) => node.id === 'upstream-admin')!, model), 'admin.example.com')
  assert.equal(resourceContext(results.find((node) => node.id === 'upstream')!, model), 'example.com, www.example.com')
  assert.notEqual(results[0].id, results[1].id)
  assert.equal(model.routes.find((route) => route.upstream.id === 'upstream-admin')?.domains[0].id, 'domain-a')
})

test('count labels use singular only for one resource', () => {
  assert.equal(countLabel(1, 'domain'), '1 domain')
  assert.equal(countLabel(2, 'domain'), '2 domains')
  assert.equal(countLabel(1, 'container'), '1 container')
  assert.equal(countLabel(0, 'network'), '0 networks')
  assert.equal(countLabel(2, 'mount'), '2 mounts')
})

test('Systemd services and listeners project to canonical product pages and relationships', () => {
  const model = resourceModel(hostServiceGraph)
  assert.deepEqual(model.services.map((node) => node.label), ['netdata.service', 'ssh.service'])
  assert.equal(model.listeners.length, 2)
  assert.deepEqual(listeningServices(model).map((node) => node.label), ['netdata.service'])
  assert.deepEqual(servicesForFilter(model, 'LISTENING').map((node) => node.label), ['netdata.service'])
  assert.deepEqual(servicesForFilter(model, 'ACTIVE').map((node) => node.label), ['netdata.service', 'ssh.service'])
  assert.deepEqual(servicesForFilter(model, 'ALL').map((node) => node.label), ['netdata.service', 'ssh.service'])
  assert.equal(pageFor(model.services[0]), 'SERVICES')
  assert.equal(pageFor(model.listeners[0]), 'NETWORK')
  assert.equal(networkSubsectionFor(model.listeners[0]), 'LISTENERS')
  assert.deepEqual(publishedPorts(model.byId.get('listener:bridge')!, model).map((node) => node.id), ['port:published'])
  assert.equal(model.routes[0].hostService?.id, 'service:netdata')
  assert.deepEqual(model.routes[0].hostListeners.map((node) => node.id), ['listener:bridge'])
})

test('service and listener search uses safe context but excludes ControlGroup and unknown metadata', () => {
  const model = resourceModel(hostServiceGraph)
  assert.equal(searchResources(hostServiceGraph, 'netdata')[0].type, 'SYSTEMD_SERVICE')
  assert.equal(searchResources(hostServiceGraph, '172.17.0.1')[0].type, 'HOST_LISTENER')
  assert.equal(resourceContext(model.byId.get('listener:bridge')!, model), 'netdata.service')
  assert.equal(searchResources(hostServiceGraph, '/system.slice').length, 0)
  assert.equal(searchResources(hostServiceGraph, 'do-not-index').length, 0)
  assert.equal(safeMetadata(model.services[0])['control group'], '/system.slice/netdata.service')
})

test('resource projection remains deterministic at the release scale target', () => {
  const graph: InfraGraph = {
    nodes: [
      { id: 'server:scale', label: 'scale-vps', type: 'SERVER', metadata: {} },
      { id: 'docker:scale', label: 'Docker', type: 'DOCKER_ENGINE', metadata: {} },
      ...Array.from({ length: 25 }, (_, index) => ({ id: `project:${index}`, label: `project-${index.toString().padStart(2, '0')}`, type: 'DOCKER_COMPOSE_PROJECT' as const, metadata: {} })),
      ...Array.from({ length: 300 }, (_, index) => ({ id: `container:${index}`, label: `service-${index.toString().padStart(3, '0')}`, type: 'DOCKER_CONTAINER' as const, metadata: { state: index % 5 ? 'running' : 'stopped' } })),
      ...Array.from({ length: 100 }, (_, index) => ({ id: `network:${index}`, label: `network-${index.toString().padStart(3, '0')}`, type: 'NETWORK' as const, metadata: {} })),
      ...Array.from({ length: 1_000 }, (_, index) => ({ id: `port:${index}`, label: `${3000 + index}/tcp`, type: 'PORT' as const, metadata: { protocol: 'tcp' } })),
      ...Array.from({ length: 1_000 }, (_, index) => ({ id: `mount:${index}`, label: `mount-${index}`, type: 'MOUNT' as const, metadata: { destination: `/data/${index}` } })),
      ...Array.from({ length: 400 }, (_, index) => ({ id: `service:${index}`, label: `worker-${index}.service`, type: 'SYSTEMD_SERVICE' as const, metadata: { 'active state': 'active' } })),
    ],
    edges: [
      ...Array.from({ length: 300 }, (_, index) => ({ id: `project-container:${index}`, source: `project:${index % 25}`, target: `container:${index}`, relation: 'CONTAINS' as const })),
      ...Array.from({ length: 1_000 }, (_, index) => ({ id: `container-port:${index}`, source: `container:${index % 300}`, target: `port:${index}`, relation: 'EXPOSES' as const })),
      ...Array.from({ length: 1_000 }, (_, index) => ({ id: `container-mount:${index}`, source: `container:${index % 300}`, target: `mount:${index}`, relation: 'MOUNTS' as const })),
    ],
  }
  const first = resourceModel(graph)
  const second = resourceModel(structuredClone(graph))
  assert.equal(first.containers.length, 300)
  assert.equal(first.applications.length, 25)
  assert.equal(first.networks.length, 100)
  assert.equal(first.ports.length, 1_000)
  assert.equal(first.mounts.length, 1_000)
  assert.equal(first.services.length, 400)
  assert.deepEqual(first.containers.map((node) => node.id), second.containers.map((node) => node.id))
  assert.ok(searchResources(graph, 'service-1').length <= 12)
})
