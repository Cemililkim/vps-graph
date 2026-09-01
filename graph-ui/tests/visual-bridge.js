(() => {
  const node = (id, label, type, metadata = {}) => ({ id, label, type, metadata })
  const mode = new URLSearchParams(location.search).get('fixture')
  const calls = { requestChanges: 0, compareSnapshots: 0, scanHost: 0 }
  window.__vpsGraphVisualCalls = calls
  if (mode === 'first-run') localStorage.removeItem('vps-graph-has-successful-scan')
  if (mode === 'prior-scan-no-profile') localStorage.setItem('vps-graph-has-successful-scan', 'true')
  const server = node('server:visual', 'demo-vps', 'SERVER', { hostname: 'demo-vps', 'operating system': 'Debian GNU/Linux', 'os version': '13', 'systemd discovery status': 'DISCOVERED', 'listener discovery status': 'DISCOVERED' })
  const docker = node('docker:visual', 'Docker', 'DOCKER_ENGINE', { 'discovery status': 'Docker discovered', 'docker discovery state': 'AVAILABLE', 'docker version': '28.3.2', 'caddy discovery status': 'Caddy routes discovered', 'caddy discovery state': 'DISCOVERED' })
  const project = node('compose:app', 'app', 'DOCKER_COMPOSE_PROJECT', { project: 'app', 'config files': 'compose.yml', 'container count': '3' })
  const n8n = node('container:n8n', 'n8n', 'DOCKER_CONTAINER', { image: 'n8nio/n8n:1.3', state: 'stopped', 'compose project': 'app', mounts: 'n8n-data' })
  const worker = node('container:worker', 'worker', 'DOCKER_CONTAINER', { image: 'worker:2.0', state: 'running', 'compose project': 'app', networks: 'edge' })
  const api = node('container:api-v2', 'api-v2', 'DOCKER_CONTAINER', { image: 'api:v2', state: 'running', 'compose project': 'app', networks: 'edge', ports: '127.0.0.1:3000 → 3000/tcp' })
  const gateway = node('container:gateway', 'gateway', 'DOCKER_CONTAINER', { image: 'traefik/whoami:v1.10', state: 'running', networks: 'edge' })
  const workerService = node('service:worker', 'worker.service', 'SYSTEMD_SERVICE', { unit: 'worker.service', 'active state': 'active', 'listener count': '0' })
  const metrics = node('service:metrics', 'metrics.service', 'SYSTEMD_SERVICE', { unit: 'metrics.service', 'active state': 'active', 'listener count': '1' })
  const caddy = node('proxy:caddy', 'Caddy', 'REVERSE_PROXY', { proxy: 'Caddy', 'container name': 'caddy', 'discovery status': 'DISCOVERED', 'route count': '2', 'domain count': '2', image: 'caddy:2.10' })
  const apiDomain = node('domain:api', 'api.example.com', 'DOMAIN', { domain: 'api.example.com', resolution: 'RESOLVED', paths: '/api/*', 'route count': '1', upstreams: 'api-v2:3000' })
  const monitor = node('domain:monitor', 'monitor.example.net', 'DOMAIN', { domain: 'monitor.example.net', resolution: 'HOST_TARGET', 'route count': '1', upstreams: 'host.docker.internal:19999' })
  const apiUpstream = node('upstream:api', 'api-v2:3000', 'UPSTREAM', { dial: 'api-v2:3000', resolution: 'RESOLVED', container: 'api-v2', 'container state': 'running', 'compose project': 'app', 'compose service': 'api' })
  const metricsUpstream = node('upstream:metrics', 'host.docker.internal:19999', 'UPSTREAM', { dial: 'host.docker.internal:19999', resolution: 'HOST_TARGET', 'host resolution': 'HOST_SERVICE', 'host service': 'metrics.service', 'host resolution reason': 'One Systemd owner matches the listener.' })
  const publicListener = node('listener:https', '0.0.0.0:443/tcp', 'HOST_LISTENER', { bind: '0.0.0.0:443/tcp', protocol: 'tcp', 'local address': '0.0.0.0', port: '443', wildcard: 'true', loopback: 'false', 'ownership state': 'SYSTEMD_SERVICE', 'systemd unit': 'metrics.service', 'process name': 'metrics', 'discovery status': 'DISCOVERED' })
  const apiPort = node('port:api', '3000/tcp', 'PORT', { 'container port': '3000', protocol: 'tcp', 'host binding': '127.0.0.1:3000', exposure: 'published' })
  const edgeNetwork = node('network:edge', 'edge', 'NETWORK', { network: 'edge', 'connected containers': '2' })
  const n8nData = node('mount:n8n-data', 'n8n-data', 'MOUNT', { type: 'volume', 'volume name': 'n8n-data', destination: '/home/node/.n8n', access: 'read-write' })
  const graph = {
    nodes: [server, docker, project, n8n, worker, api, gateway, workerService, metrics, caddy, apiDomain, monitor, apiUpstream, metricsUpstream, publicListener, apiPort, edgeNetwork, n8nData],
    edges: [
      { id: 'runs-docker', source: server.id, target: docker.id, relation: 'RUNS' },
      { id: 'contains-project', source: docker.id, target: project.id, relation: 'CONTAINS' },
      { id: 'contains-n8n', source: project.id, target: n8n.id, relation: 'CONTAINS' },
      { id: 'contains-worker', source: project.id, target: worker.id, relation: 'CONTAINS' },
      { id: 'contains-api', source: project.id, target: api.id, relation: 'CONTAINS' },
      { id: 'contains-gateway', source: docker.id, target: gateway.id, relation: 'CONTAINS' },
      { id: 'api-domain-route', source: apiDomain.id, target: apiUpstream.id, relation: 'ROUTES_TO' },
      { id: 'monitor-domain-route', source: monitor.id, target: metricsUpstream.id, relation: 'ROUTES_TO' },
      { id: 'caddy-api', source: caddy.id, target: apiUpstream.id, relation: 'PROXIES_TO' },
      { id: 'caddy-metrics', source: caddy.id, target: metricsUpstream.id, relation: 'PROXIES_TO' },
      { id: 'api-target', source: apiUpstream.id, target: api.id, relation: 'RESOLVES_TO' },
      { id: 'metrics-target', source: metricsUpstream.id, target: publicListener.id, relation: 'TARGETS' },
      { id: 'listener-owner', source: publicListener.id, target: metrics.id, relation: 'OWNED_BY' },
      { id: 'api-port', source: api.id, target: apiPort.id, relation: 'EXPOSES' },
      { id: 'listener-published-port', source: publicListener.id, target: apiPort.id, relation: 'PUBLISHED_AS' },
      { id: 'api-edge-network', source: api.id, target: edgeNetwork.id, relation: 'CONNECTED_TO' },
      { id: 'worker-edge-network', source: worker.id, target: edgeNetwork.id, relation: 'CONNECTED_TO' },
      { id: 'gateway-edge-network', source: gateway.id, target: edgeNetwork.id, relation: 'CONNECTED_TO' },
      { id: 'n8n-data-mount', source: n8n.id, target: n8nData.id, relation: 'MOUNTS' },
    ],
  }
  if (mode === 'minimal' || mode === 'docker-absent') {
    graph.nodes = [server, docker]
    graph.edges = [{ id: 'runs-docker', source: server.id, target: docker.id, relation: 'RUNS' }]
    docker.label = 'Docker unavailable'
    docker.metadata['discovery status'] = 'Docker unavailable'
    docker.metadata['docker discovery state'] = 'DOCKER_UNAVAILABLE'
    docker.metadata['caddy discovery status'] = 'Caddy status unknown'
    docker.metadata['caddy discovery state'] = 'UNKNOWN'
    delete docker.metadata['docker version']
    server.metadata['systemd discovery status'] = 'NOT_AVAILABLE'
    server.metadata['listener discovery status'] = 'NOT_AVAILABLE'
  }
  if (mode === 'helper-unavailable' || mode === 'helper-unauthorized' || mode === 'helper-malformed') {
    graph.nodes = [server, docker]
    graph.edges = [{ id: 'runs-docker', source: server.id, target: docker.id, relation: 'RUNS' }]
    docker.label = 'Docker unavailable'
    docker.metadata['docker discovery state'] = mode === 'helper-unavailable' ? 'HELPER_NOT_INSTALLED' : mode === 'helper-unauthorized' ? 'HELPER_NOT_AUTHORIZED' : 'SCAN_FAILED'
    docker.metadata['discovery status'] = mode === 'helper-unavailable' ? 'Docker helper not installed' : mode === 'helper-unauthorized' ? 'Docker access not configured' : 'Docker scan partial'
    docker.metadata['caddy discovery state'] = 'NOT_DETECTED'
    delete docker.metadata['docker version']
    server.metadata['systemd discovery status'] = 'UNKNOWN'
    server.metadata['listener discovery status'] = 'UNKNOWN'
  }
  if (mode === 'helper-old-schema') {
    server.metadata['systemd discovery status'] = 'UNKNOWN'
    server.metadata['listener discovery status'] = 'UNKNOWN'
  }
  if (mode === 'systemd-unavailable') {
    graph.nodes = graph.nodes.filter((item) => item.type !== 'SYSTEMD_SERVICE')
    server.metadata['systemd discovery status'] = 'NOT_SYSTEMD'
    server.metadata['listener discovery status'] = 'NOT_SYSTEMD'
  }
  const snapshotNode = (value) => ({ id: value.id, label: value.label, type: value.type, metadata: value.metadata })
  const previousN8n = node(n8n.id, n8n.label, n8n.type, { image: 'n8nio/n8n:1.2', state: 'running', 'compose project': 'app' })
  const oldWorker = node('container:old-worker', 'old-worker', 'DOCKER_CONTAINER', { image: 'worker:1.3', state: 'running', 'compose project': 'app' })
  const escaped = node('service:sync-agent', 'sync-agent.service', 'SYSTEMD_SERVICE', { unit: 'sync-agent.service' })
  const scoped = node('listener:legacy-metrics', '0.0.0.0:9100/tcp', 'HOST_LISTENER', { 'local address': '0.0.0.0', port: '9100' })
  const oldApi = node('container:api-v1', 'api-v1', 'DOCKER_CONTAINER')
  const oldNetdata = node('service:metrics-legacy', 'metrics-legacy.service', 'SYSTEMD_SERVICE')
  const history = [
    { snapshotId: '22222222-2222-4222-8222-222222222222', capturedAt: '2026-08-30T11:42:00Z', fingerprint: '2222222222' },
    { snapshotId: '11111111-1111-4111-8111-111111111111', capturedAt: '2026-08-30T08:05:00Z', fingerprint: '1111111111' },
    { snapshotId: '00000000-0000-4000-8000-000000000000', capturedAt: '2026-08-29T19:45:00Z', fingerprint: '0000000000' },
  ]
  const emptySummary = { addedNodes: 0, removedNodes: 0, modifiedNodes: 0, addedRelationships: 0, removedRelationships: 0, uncertainComparisons: 0, hasChanges: false, isComplete: true }
  const changed = {
    schemaVersion: 1,
    comparison: { previous: { ...history[1], persisted: true }, current: { ...history[0], persisted: true } },
    diff: {
      previousSnapshotId: history[1].snapshotId,
      currentSnapshotId: history[0].snapshotId,
      previousCapturedAt: history[1].capturedAt,
      currentCapturedAt: history[0].capturedAt,
      status: 'PARTIAL',
      summary: { addedNodes: 2, removedNodes: 1, modifiedNodes: 1, addedRelationships: 2, removedRelationships: 2, uncertainComparisons: 4, hasChanges: true, isComplete: false },
      nodeChanges: [
        { id: 'modified-n8n', kind: 'MODIFIED', evidence: 'CONFIRMED', nodeId: n8n.id, nodeType: n8n.type, label: n8n.label, fields: [{ field: 'metadata:state', before: 'running', after: 'stopped' }, { field: 'metadata:image', before: previousN8n.metadata.image, after: n8n.metadata.image }] },
        { id: 'added-worker', kind: 'ADDED', evidence: 'CONFIRMED', nodeId: worker.id, nodeType: worker.type, label: worker.label, fields: [] },
        { id: 'added-worker-service', kind: 'ADDED', evidence: 'NEWLY_OBSERVED', nodeId: workerService.id, nodeType: workerService.type, label: workerService.label, fields: [] },
        { id: 'removed-old-worker', kind: 'REMOVED', evidence: 'CONFIRMED', nodeId: oldWorker.id, nodeType: oldWorker.type, label: oldWorker.label, fields: [] },
        { id: 'uncertain-escaped', kind: 'REMOVED', evidence: 'UNCONFIRMED_REMOVAL', nodeId: escaped.id, nodeType: escaped.type, label: escaped.label, fields: [] },
        { id: 'uncertain-scoped', kind: 'REMOVED', evidence: 'UNCONFIRMED_REMOVAL', nodeId: scoped.id, nodeType: scoped.type, label: scoped.label, fields: [] },
      ],
      relationshipChanges: [
        { id: 'api-old', kind: 'RELATIONSHIP_REMOVED', evidence: 'CONFIRMED', edge: { id: 'api-old', source: apiDomain.id, target: oldApi.id, relation: 'RESOLVES_TO' } },
        { id: 'api-new', kind: 'RELATIONSHIP_ADDED', evidence: 'CONFIRMED', edge: { id: 'api-new', source: apiDomain.id, target: api.id, relation: 'RESOLVES_TO' } },
        { id: 'monitor-old', kind: 'RELATIONSHIP_REMOVED', evidence: 'CONFIRMED', edge: { id: 'monitor-old', source: monitor.id, target: oldNetdata.id, relation: 'RESOLVES_TO' } },
        { id: 'monitor-new', kind: 'RELATIONSHIP_ADDED', evidence: 'CONFIRMED', edge: { id: 'monitor-new', source: monitor.id, target: metrics.id, relation: 'RESOLVES_TO' } },
      ],
      uncertainties: [
        { subsystem: 'SYSTEMD', reason: 'CURRENT_DISCOVERY_INCOMPLETE', affectedResourceTypes: ['SYSTEMD_SERVICE'] },
        { subsystem: 'HOST_LISTENERS', reason: 'CURRENT_DISCOVERY_INCOMPLETE', affectedResourceTypes: ['HOST_LISTENER'] },
      ],
    },
    resources: [
      { nodeId: n8n.id, before: snapshotNode(previousN8n), after: snapshotNode(n8n) },
      { nodeId: worker.id, after: snapshotNode(worker) },
      { nodeId: workerService.id, after: snapshotNode(workerService) },
      { nodeId: oldWorker.id, before: snapshotNode(oldWorker) },
      { nodeId: escaped.id, before: snapshotNode(escaped) },
      { nodeId: scoped.id, before: snapshotNode(scoped) },
      { nodeId: apiDomain.id, before: snapshotNode(apiDomain), after: snapshotNode(apiDomain) },
      { nodeId: oldApi.id, before: snapshotNode(oldApi) },
      { nodeId: api.id, after: snapshotNode(api) },
      { nodeId: monitor.id, before: snapshotNode(monitor), after: snapshotNode(monitor) },
      { nodeId: oldNetdata.id, before: snapshotNode(oldNetdata) },
      { nodeId: metrics.id, after: snapshotNode(metrics) },
    ],
    history,
  }
  const payload = mode === 'baseline' ? { ...changed, comparison: { current: changed.comparison.current }, diff: { ...changed.diff, previousSnapshotId: null, previousCapturedAt: null, status: 'NO_BASELINE', summary: { ...emptySummary, isComplete: false }, nodeChanges: [], relationshipChanges: [], uncertainties: [] }, resources: [], history: [history[0]] }
    : mode === 'no-changes' ? { ...changed, diff: { ...changed.diff, status: 'NO_CHANGES', summary: emptySummary, nodeChanges: [], relationshipChanges: [], uncertainties: [] }, resources: [] }
      : changed
  if (mode === 'legacy-kotlin-no-changes') {
    payload.diff.status = 'NO_CHANGES'
    payload.diff.summary = {}
    delete payload.diff.nodeChanges
    delete payload.diff.relationshipChanges
    delete payload.diff.uncertainties
    payload.resources = []
  }
  const response = { ok: true, payload }
  window.vpsGraph = {
    requestGraph: async () => JSON.stringify(graph),
    requestConnectionPreferences: async () => JSON.stringify(mode === 'returning-user' || mode === 'remembered-connected' || mode === 'missing-key' ? { remembered: true, host: 'vps.example', port: 22, username: 'vpsgraph', privateKeyPath: mode === 'missing-key' ? 'C:\\keys\\missing_ed25519' : 'C:\\keys\\id_ed25519', privateKeyExists: mode !== 'missing-key' } : { remembered: false }),
    requestChanges: async () => { calls.requestChanges += 1; return JSON.stringify(response) },
    compareSnapshots: async () => { calls.compareSnapshots += 1; return JSON.stringify(response) },
    scanHost: async () => { calls.scanHost += 1; return JSON.stringify({ accepted: true }) },
    choosePrivateKey: async () => JSON.stringify(''),
  }
  window.addEventListener('DOMContentLoaded', () => setTimeout(() => {
    if (mode === 'first-run' || mode === 'returning-user' || mode === 'missing-key' || mode === 'prior-scan-no-profile') return
    const connectionErrors = {
      'connection-error': 'SSH_HOST_UNREACHABLE',
      'authentication-error': 'SSH_AUTH_FAILED',
      'timeout-error': 'SSH_TIMEOUT',
      'refused-error': 'SSH_CONNECTION_REFUSED',
      'unknown-host-key': 'SSH_HOST_KEY_UNKNOWN',
      'host-key-mismatch': 'SSH_HOST_KEY_MISMATCH',
    }
    if (connectionErrors[mode]) {
      window.dispatchEvent(new CustomEvent('vps-graph-scan-status', { detail: { state: 'ERROR', errorCode: connectionErrors[mode], errorMessage: 'Raw transport text must not be shown.' } }))
      return
    }
    window.dispatchEvent(new CustomEvent('vps-graph-scan-status', { detail: { state: 'CONNECTED', graph, changes: response } }))
    if (mode === 'rescan-error') setTimeout(() => window.dispatchEvent(new CustomEvent('vps-graph-scan-status', { detail: { state: 'ERROR', errorMessage: 'The latest scan timed out.' } })), 120)
  }, 80))
})()
