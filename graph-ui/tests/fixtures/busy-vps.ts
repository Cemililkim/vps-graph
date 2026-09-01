import type { InfraGraph, InfraNode } from '../../src/types.ts'

const node = (id: string, label: string, type: InfraNode['type'], metadata: Record<string, string> = {}): InfraNode => ({ id, label, type, metadata })

export const busyVpsGraph: InfraGraph = (() => {
  const nodes: InfraNode[] = [node('server:busy', 'busy-vps', 'SERVER'), node('docker:busy', 'Docker', 'DOCKER_ENGINE')]
  const edges = [{ id: 'server-runs-docker', source: 'server:busy', target: 'docker:busy', relation: 'RUNS' as const }]
  for (let project = 1; project <= 10; project += 1) {
    const projectId = `compose:${project}`
    nodes.push(node(projectId, `project-${project}`, 'DOCKER_COMPOSE_PROJECT', { 'container count': '4' }))
    edges.push({ id: `docker-${projectId}`, source: 'docker:busy', target: projectId, relation: 'CONTAINS' as const })
    for (let container = 1; container <= 4; container += 1) {
      const containerId = `${projectId}:container:${container}`
      nodes.push(node(containerId, `project-${project}-service-${container}`, 'DOCKER_CONTAINER', { image: `image-${container}`, state: 'running', networks: 'web, internal', ports: `${3000 + container}/tcp`, mounts: '/srv/data' }))
      edges.push({ id: `${projectId}-${containerId}`, source: projectId, target: containerId, relation: 'CONTAINS' as const })
      const portId = `${containerId}:port`
      const mountId = `${containerId}:mount`
      nodes.push(node(portId, `${3000 + container}/tcp`, 'PORT', { 'container port': `${3000 + container}`, protocol: 'tcp', exposure: 'internal only' }))
      nodes.push(node(mountId, `data-${project}-${container}`, 'MOUNT', { type: 'bind', source: `/srv/project-${project}/data-${container}`, destination: '/app/data', access: 'read-write' }))
      edges.push({ id: `${containerId}-port`, source: containerId, target: portId, relation: 'EXPOSES' as const })
      edges.push({ id: `${containerId}-mount`, source: containerId, target: mountId, relation: 'MOUNTS' as const })
    }
  }
  for (const networkName of ['web', 'internal', 'metrics']) {
    const networkId = `network:${networkName}`
    nodes.push(node(networkId, networkName, 'NETWORK', { 'connected containers': '40' }))
    nodes.filter((item) => item.type === 'DOCKER_CONTAINER' && (networkName !== 'metrics' || item.label.endsWith('-1'))).forEach((container) => edges.push({ id: `${container.id}-${networkId}`, source: container.id, target: networkId, relation: 'CONNECTED_TO' as const }))
  }
  return { nodes, edges }
})()
