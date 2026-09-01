import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Background, Controls, Handle, Position, ReactFlow, type Edge, type Node, type NodeProps, type ReactFlowInstance } from '@xyflow/react'
import { Activity, Archive, ArrowRight, Box, Boxes, ChevronDown, ChevronRight, CircleDot, Database, Globe2, History, KeyRound, LayoutPanelLeft, LogOut, Moon, Network, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, RadioTower, Search, Server, Settings2, Share2, Sun, Waypoints, X } from 'lucide-react'
import '@xyflow/react/dist/style.css'
import { deriveVisibleGraph, toFlowGraph } from './graph-utils'
import { compactPath, connectedContainers, containerState, countLabel, listeningServices, networkSubsectionFor, pageFor, publishedPorts, relatedResources, resourceContext, resourceModel, routedDomains, safeMetadata, searchResources, servicesForFilter, type NetworkSubsection, type PrimarySection, type ServiceFilter } from './resource-model'
import type { ChangesPayload, ChangesResponse, InfraGraph, InfraNode, ScanAcknowledgement, ScanStatusEvent } from './types'
import { caddyEmptyCopy, canDisconnect, connectionIssue, disconnectedFormState, discoveryStatusCopy, dockerEmptyCopy, nextTheme, panelDefaults, resolveTheme, scanSessionState, validateConnection, type ConnectionIssue, type SetupTopic, type ThemeMode } from './ui-state'
import { parseConnectionPreferences } from './connection-preferences'
import { confirmedChangeCount, normalizeChangesResponse, overviewChangeSummary, parseChangesResponse } from './changes-model'
import { ChangeInspector, ChangesErrorBoundary, ChangesPage, type ChangeSelection } from './ChangesPage'
import { ConnectionIssueCard, ConnectionOrientation, HelperStatus, SetupGuide } from './Onboarding'
import { installNativeDragGuard } from './native-drag-guard'
import vpsGraphLogo from './assets/vps-graph-logo.svg'
import vpsGraphLogoDark from './assets/vps-graph-logo-dark.svg'

type FlowNode = Node<InfraNode & Record<string, unknown>, 'infrastructure'>

function readGraph(serialized: string): InfraGraph {
  const graph: unknown = JSON.parse(serialized)
  if (typeof graph !== 'object' || graph === null || !Array.isArray((graph as InfraGraph).nodes) || !Array.isArray((graph as InfraGraph).edges)) throw new Error('The plugin returned an invalid infrastructure graph.')
  return graph as InfraGraph
}

function StateLabel({ value }: { value: string }) {
  const running = value === 'running'
  return <span className={`state-label ${running ? 'state-label--running' : ''}`}><CircleDot size={12} /> {value}</span>
}

function ServiceCard({ node, selected, routedCount, onSelect }: { node: InfraNode; selected: boolean; routedCount: number; onSelect: (node: InfraNode) => void }) {
  const metadata = safeMetadata(node)
  const ports = metadata.ports?.split(',').filter(Boolean) ?? []
  return <button type="button" className={`service-card ${selected ? 'is-selected' : ''}`} aria-pressed={selected} onClick={() => onSelect(node)}>
    <span className="service-card__title">{node.label}</span>
    <StateLabel value={containerState(node)} />
    <span className="service-card__meta">{metadata['compose service'] ?? metadata.image ?? 'Container'}</span>
    {ports[0] && <span className="service-card__port">{ports[0]}</span>}
    <span className="service-card__summary">{countLabel(metadata.networks?.split(',').filter(Boolean).length ?? 0, 'network')} · {countLabel(metadata.mounts?.split(',').filter(Boolean).length ?? 0, 'mount')}{routedCount ? ` · ${countLabel(routedCount, 'domain')}` : ''}</span>
  </button>
}

function TopologyNode({ data, selected }: NodeProps<FlowNode>) {
  const metadata = safeMetadata(data)
  const state = data.type === 'DOCKER_CONTAINER' ? containerState(data) : undefined
  return <>
    <Handle type="target" position={Position.Left} />
    <article className={`topology-node ${selected ? 'topology-node--selected' : ''}`}>
      <strong>{data.label}</strong>
      <small>{data.type === 'DOCKER_CONTAINER' ? metadata['compose service'] ?? metadata.image ?? 'Container' : data.type.replaceAll('_', ' ')}</small>
      {state && <StateLabel value={state} />}
    </article>
    <Handle type="source" position={Position.Right} />
  </>
}

const topologyNodeTypes = { infrastructure: TopologyNode }
const SUCCESSFUL_SCAN_KEY = 'vps-graph-has-successful-scan'
const WIDTH_HINT_KEY = 'vps-graph-width-hint-dismissed'

function storedTheme(): ThemeMode {
  try { return resolveTheme(window.localStorage.getItem('vps-graph-theme'), window.matchMedia('(prefers-color-scheme: light)').matches) }
  catch { return 'dark' }
}

function successfulScanStored(): boolean {
  try { return window.localStorage.getItem(SUCCESSFUL_SCAN_KEY) === 'true' }
  catch { return false }
}

function ThemeToggle({ theme, onToggle }: { theme: ThemeMode; onToggle: () => void }) {
  const label = `Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`
  return <button type="button" className="icon-button theme-toggle" onClick={onToggle} aria-label={label} title={label}>
    <span className={`theme-toggle__icon ${theme === 'dark' ? 'is-active' : ''}`}><Sun size={16} /></span>
    <span className={`theme-toggle__icon ${theme === 'light' ? 'is-active' : ''}`}><Moon size={16} /></span>
  </button>
}

export default function App() {
  const [theme, setTheme] = useState<ThemeMode>(storedTheme)
  const [graph, setGraph] = useState<InfraGraph | null>(null)
  const [scanState, setScanState] = useState<'IDLE' | ScanStatusEvent['state']>('IDLE')
  const [bridgeError, setBridgeError] = useState<string | null>(null)
  const [scanIssue, setScanIssue] = useState<ConnectionIssue | null>(null)
  const [host, setHost] = useState('')
  const [port, setPort] = useState('22')
  const [username, setUsername] = useState('')
  const [privateKeyPath, setPrivateKeyPath] = useState('')
  const [rememberConnection, setRememberConnection] = useState(false)
  const [rememberedKeyMissing, setRememberedKeyMissing] = useState(false)
  const [changesPayload, setChangesPayload] = useState<ChangesPayload | null>(null)
  const [changesLoading, setChangesLoading] = useState(false)
  const [changesError, setChangesError] = useState<string | null>(null)
  const [selectedChange, setSelectedChange] = useState<ChangeSelection | null>(null)
  const [scanFeedback, setScanFeedback] = useState<string | null>(null)
  const [section, setSection] = useState<PrimarySection>('OVERVIEW')
  const [networkSubsection, setNetworkSubsection] = useState<NetworkSubsection | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchIndex, setSearchIndex] = useState(0)
  const [explorerOpen, setExplorerOpen] = useState(() => panelDefaults(window.innerWidth).explorerOpen)
  const [inspectorOpen, setInspectorOpen] = useState(() => panelDefaults(window.innerWidth).inspectorOpen)
  const [connectionSettingsOpen, setConnectionSettingsOpen] = useState(false)
  const [setupTopic, setSetupTopic] = useState<SetupTopic | null>(null)
  const [connectionPreferencesLoaded, setConnectionPreferencesLoaded] = useState(false)
  const [hasRememberedConnection, setHasRememberedConnection] = useState(false)
  const [hasSuccessfulScan, setHasSuccessfulScan] = useState(successfulScanStored)
  const [widthHintDismissed, setWidthHintDismissed] = useState(() => { try { return window.localStorage.getItem(WIDTH_HINT_KEY) === 'true' } catch { return false } })
  const [collapsedExplorer, setCollapsedExplorer] = useState<Set<string>>(() => new Set(['routing']))
  const [topologyOpen, setTopologyOpen] = useState(false)
  const [focusedNodeId, setFocusedNodeId] = useState<string | null>(null)
  const flowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const successfulScanRef = useRef(false)
  const explorerChoiceMadeRef = useRef(false)
  const [hasSuccessfulGraph, setHasSuccessfulGraph] = useState(false)
  const active = scanState === 'CONNECTING' || scanState === 'SCANNING'
  const connected = hasSuccessfulGraph
  const brandLogo = theme === 'dark' ? vpsGraphLogoDark : vpsGraphLogo
  const model = useMemo(() => graph ? resourceModel(graph) : undefined, [graph])
  const selected = selectedId && model ? model.byId.get(selectedId) ?? null : null
  const searchResults = useMemo(() => graph ? searchResources(graph, searchQuery) : [], [graph, searchQuery])
  const topology = useMemo(() => graph ? deriveVisibleGraph(graph, 'OVERVIEW', { focusedNodeId }) : { nodes: [], edges: [] }, [focusedNodeId, graph])
  const topologyFlow = useMemo(() => toFlowGraph(topology, 'OVERVIEW', selectedId), [selectedId, topology])
  const changesCount = confirmedChangeCount(changesPayload)

  useEffect(() => installNativeDragGuard(), [])

  const applyChangesResponse = useCallback((response: ChangesResponse, scanCompleted = false) => {
    if (!response.ok || !response.payload) {
      setChangesError(response.error?.message ?? 'Local change history is unavailable.')
      if (scanCompleted) setScanFeedback('History unavailable')
      return
    }
    setChangesPayload(response.payload)
    setChangesError(null)
    if (scanCompleted) {
      const status = response.payload.diff.status
      const count = confirmedChangeCount(response.payload)
      setScanFeedback(status === 'NO_BASELINE' ? 'Baseline captured' : status === 'NO_CHANGES' ? 'No changes' : status === 'PARTIAL' && count === 0 ? 'Comparison incomplete' : `${count} changes`)
    }
  }, [])

  const requestChanges = useCallback(() => {
    if (!window.vpsGraph) return
    setChangesLoading(true)
    window.vpsGraph.requestChanges().then(parseChangesResponse).then((response) => applyChangesResponse(response)).catch(() => setChangesError('Local change history is unavailable.')).finally(() => setChangesLoading(false))
  }, [applyChangesResponse])

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    try { window.localStorage.setItem('vps-graph-theme', theme) } catch { /* Local theme persistence is optional. */ }
  }, [theme])

  useEffect(() => {
    const compact = window.matchMedia('(max-width: 820px)')
    const adaptPanels = (event: MediaQueryListEvent) => { if (event.matches) { setExplorerOpen(false); setInspectorOpen(false) } }
    compact.addEventListener('change', adaptPanels)
    return () => compact.removeEventListener('change', adaptPanels)
  }, [])

  const fitTopology = useCallback(() => window.requestAnimationFrame(() => flowRef.current?.fitView({ padding: 0.24, duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 180 })), [])

  useEffect(() => {
    let disposed = false
    const timeout = window.setTimeout(() => { if (!window.vpsGraph && !disposed) setBridgeError('The local VPS Graph bridge is unavailable in this IDE session.') }, 5000)
    const loadGraph = () => window.vpsGraph?.requestGraph().then(readGraph).then((value) => { if (!disposed) setGraph(value) }).catch((reason: unknown) => { if (!disposed) setBridgeError(reason instanceof Error ? reason.message : 'Unable to load graph data.') })
    const loadConnectionPreferences = () => window.vpsGraph?.requestConnectionPreferences().then(parseConnectionPreferences).then((value) => {
      if (disposed) return
      setConnectionPreferencesLoaded(true); setHasRememberedConnection(value.remembered)
      if (!value.remembered) return
      setHost(value.host); setPort(value.port); setUsername(value.username); setPrivateKeyPath(value.privateKeyPath); setRememberConnection(true); setRememberedKeyMissing(value.privateKeyMissing)
      if (value.privateKeyMissing) setScanIssue(connectionIssue('SSH_KEY_NOT_FOUND'))
    }).catch(() => { if (!disposed) setConnectionPreferencesLoaded(true) /* Remembered form convenience must never block the Tool Window. */ })
    const loadInitialState = () => { loadGraph(); loadConnectionPreferences() }
    const receive = (event: Event) => {
      const detail = (event as CustomEvent<ScanStatusEvent>).detail
      if (!detail || disposed) return
      const firstSuccessfulPresentation = !successfulScanRef.current && detail.state === 'CONNECTED'
      const session = scanSessionState(successfulScanRef.current, detail.state)
      successfulScanRef.current = session.hasSuccessfulGraph
      setHasSuccessfulGraph(session.hasSuccessfulGraph)
      setScanState(detail.state)
      if (detail.state === 'CONNECTED' && detail.graph) { setGraph(detail.graph); setSelectedId(null); setSelectedChange(null); setSection('OVERVIEW'); setNetworkSubsection(null); setFocusedNodeId(null); setScanIssue(null); setConnectionSettingsOpen(false); setHasSuccessfulScan(true); if (firstSuccessfulPresentation && !explorerChoiceMadeRef.current && window.innerWidth > 820) { setExplorerOpen(true); setInspectorOpen(false) } try { window.localStorage.setItem(SUCCESSFUL_SCAN_KEY, 'true') } catch { /* The hint may reappear if local UI storage is unavailable. */ } if (detail.changes) applyChangesResponse(normalizeChangesResponse(detail.changes), true); else requestChanges() }
      if (detail.state === 'ERROR') setScanIssue(connectionIssue(detail.errorCode))
    }
    if (window.vpsGraph) loadInitialState(); else window.addEventListener('vps-graph-bridge-ready', loadInitialState, { once: true })
    window.addEventListener('vps-graph-scan-status', receive)
    return () => { disposed = true; window.clearTimeout(timeout); window.removeEventListener('vps-graph-bridge-ready', loadInitialState); window.removeEventListener('vps-graph-scan-status', receive) }
  }, [applyChangesResponse, requestChanges])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && connected) { event.preventDefault(); setSearchOpen(true); window.requestAnimationFrame(() => searchRef.current?.focus()) }
      if (!searchOpen) return
      if (event.key === 'ArrowDown') { event.preventDefault(); setSearchIndex((index) => Math.min(index + 1, Math.max(0, searchResults.length - 1))) }
      if (event.key === 'ArrowUp') { event.preventDefault(); setSearchIndex((index) => Math.max(index - 1, 0)) }
      if (event.key === 'Enter' && searchResults[searchIndex]) { event.preventDefault(); selectResource(searchResults[searchIndex]) }
      if (event.key === 'Escape') { event.preventDefault(); setSearchOpen(false); setSearchQuery('') }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [connected, searchIndex, searchOpen, searchResults])

  useEffect(() => { if (topologyOpen) fitTopology() }, [fitTopology, focusedNodeId, topologyOpen, topology])

  const requestScan = (form?: HTMLFormElement) => {
    if (active || !window.vpsGraph) return
    const invalid = validateConnection({ host, port, username, privateKeyPath })
    if (invalid) {
      setScanIssue(invalid); setScanState('IDLE')
      window.requestAnimationFrame(() => (form?.elements.namedItem(invalid.field ?? '') as HTMLElement | null)?.focus())
      return
    }
    setScanIssue(null); setScanFeedback(null); setScanState('CONNECTING')
    window.vpsGraph.scanHost({ host, port: Number(port), username, privateKeyPath, rememberConnection }).then((serialized) => JSON.parse(serialized) as ScanAcknowledgement).then((response) => {
      if (!response.accepted) { setScanState('ERROR'); setScanIssue(connectionIssue(response.errorCode)) }
    }).catch(() => { setScanState('ERROR'); setScanIssue(connectionIssue()) })
  }
  const submitScan = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); requestScan(event.currentTarget) }
  const choosePrivateKey = () => {
    if (active || !window.vpsGraph) return
    window.vpsGraph.choosePrivateKey().then((serialized) => JSON.parse(serialized) as string).then((path) => { if (path) { setPrivateKeyPath(path); setRememberedKeyMissing(false); setScanIssue(null) } }).catch(() => setScanIssue(connectionIssue('PRIVATE_KEY_PICKER_FAILED')))
  }
  const selectResource = (node: InfraNode) => {
    setSelectedChange(null); setSelectedId(node.id); setSection(pageFor(node)); setNetworkSubsection(networkSubsectionFor(node)); setInspectorOpen(true); if (window.innerWidth <= 820) setExplorerOpen(false); setSearchOpen(false); setSearchQuery(''); setTopologyOpen(false); setFocusedNodeId(null)
  }
  const openNetworkSubsection = (subsection: NetworkSubsection) => {
    const target = subsection === 'NETWORKS' ? model?.networks[0] : subsection === 'PORTS' ? model?.ports[0] : model?.listeners[0]
    setSection('NETWORK'); setNetworkSubsection(subsection); setSelectedId(target?.id ?? null); setInspectorOpen(Boolean(target)); setTopologyOpen(false); setFocusedNodeId(null); if (window.innerWidth <= 820) setExplorerOpen(false)
  }
  const selectTopologyResource = (node: InfraNode) => {
    setSelectedId(node.id); setSection('APPLICATIONS'); setInspectorOpen(true)
  }
  const toggleExplorerItem = (id: string) => setCollapsedExplorer((current) => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next })
  const revealTopology = () => {
    if (!selected) return
    setSection('APPLICATIONS'); setTopologyOpen(true); setFocusedNodeId(selected.id)
  }
  const toggleTheme = () => setTheme((current) => nextTheme(current))
  const toggleExplorer = () => { explorerChoiceMadeRef.current = true; setExplorerOpen((open) => !open) }
  const dismissWidthHint = () => { setWidthHintDismissed(true); try { window.localStorage.setItem(WIDTH_HINT_KEY, 'true') } catch { /* Dismissal persistence is optional. */ } }
  const disconnect = () => {
    if (!canDisconnect(scanState)) return
    const fields = disconnectedFormState({ host, port, username, privateKeyPath, rememberConnection })
    successfulScanRef.current = false
    setHasSuccessfulGraph(false); setScanState('IDLE'); setScanIssue(null); setScanFeedback(null); setChangesPayload(null); setChangesError(null); setSelectedChange(null); setSelectedId(null); setSection('OVERVIEW'); setNetworkSubsection(null); setTopologyOpen(false); setFocusedNodeId(null); setInspectorOpen(false); setConnectionSettingsOpen(false)
    setHost(fields.host); setPort(fields.port); setUsername(fields.username); setPrivateKeyPath(fields.privateKeyPath); setRememberConnection(fields.rememberConnection); setRememberedKeyMissing(false)
  }
  const openChanges = () => { setSection('CHANGES'); setSelectedId(null); setSelectedChange(null); setTopologyOpen(false); setFocusedNodeId(null); if (!changesPayload) requestChanges(); if (window.innerWidth <= 820) { setExplorerOpen(false); setInspectorOpen(false) } }
  const compareSnapshots = (previousSnapshotId: string, currentSnapshotId: string) => {
    if (!window.vpsGraph || changesLoading) return
    setChangesLoading(true); setChangesError(null); setSelectedChange(null)
    window.vpsGraph.compareSnapshots(previousSnapshotId, currentSnapshotId).then(parseChangesResponse).then((response) => applyChangesResponse(response)).catch(() => setChangesError('The selected snapshots could not be compared.')).finally(() => setChangesLoading(false))
  }
  const inspectChange = (selection: ChangeSelection) => { setSelectedChange(selection); setSelectedId(null); setInspectorOpen(true) }
  const navigateFromChange = (nodeId: string) => { const node = model?.byId.get(nodeId); if (node) selectResource(node); else setChangesError('This historical resource is not present in the current infrastructure.') }

  const firstRun = connectionPreferencesLoaded && !hasRememberedConnection && !hasSuccessfulScan
  const showFieldHelp = firstRun && !connected
  const fieldInvalid = (field: string) => rememberedKeyMissing && field === 'privateKeyPath' || scanIssue?.field === field
  const connectionForm = <form className="connection-form" onSubmit={submitScan} aria-describedby={scanIssue ? 'connection-issue' : undefined} noValidate>
    <label htmlFor="ssh-host">Host<input id="ssh-host" name="host" value={host} onChange={(event) => { setHost(event.target.value); setScanIssue(null) }} disabled={active} required aria-invalid={fieldInvalid('host')} aria-describedby={showFieldHelp ? 'ssh-host-help' : undefined} placeholder="vps.example" />{showFieldHelp && <small id="ssh-host-help">Hostname, IPv4, or IPv6 address.</small>}</label>
    <label htmlFor="ssh-port">Port<input id="ssh-port" name="port" type="number" min="1" max="65535" value={port} onChange={(event) => { setPort(event.target.value); setScanIssue(null) }} disabled={active} required aria-invalid={fieldInvalid('port')} /></label>
    <label htmlFor="ssh-username">Username<input id="ssh-username" name="username" value={username} onChange={(event) => { setUsername(event.target.value); setScanIssue(null) }} disabled={active} required aria-invalid={fieldInvalid('username')} aria-describedby={showFieldHelp ? 'ssh-user-help' : undefined} placeholder="vpsgraph" />{showFieldHelp && <small id="ssh-user-help">Linux account used for read-only discovery; root is not required.</small>}</label>
    <label className="connection-form__key" htmlFor="ssh-private-key">Private key<input id="ssh-private-key" name="privateKeyPath" value={privateKeyPath} onChange={(event) => { setPrivateKeyPath(event.target.value); setRememberedKeyMissing(false); setScanIssue(null) }} disabled={active} required aria-invalid={fieldInvalid('privateKeyPath')} aria-describedby={showFieldHelp ? 'ssh-key-help' : undefined} placeholder="Path to an existing private key" />{showFieldHelp && <small id="ssh-key-help">Read locally for SSH authentication. The key stays on this computer.</small>}</label>
    <button type="button" className="quiet-button connection-form__browse" onClick={choosePrivateKey} disabled={active} aria-label="Browse for private key" title="Browse for private key"><KeyRound size={15} /> Browse</button>
    {rememberedKeyMissing && <span className="connection-form__key-error" role="alert">Remembered key file was not found.</span>}
    <div className="connection-form__actions">
      <label className="connection-form__remember"><input type="checkbox" checked={rememberConnection} onChange={(event) => setRememberConnection(event.target.checked)} disabled={active} /> <span>Remember this connection</span></label>
      <button className="primary-button" disabled={active}>{active ? (scanState === 'CONNECTING' ? 'Connecting…' : 'Scanning…') : 'Connect & Scan'}</button>
    </div>
  </form>

  if (bridgeError) return <main className="status-panel"><ThemeToggle theme={theme} onToggle={toggleTheme} /><h1>VPS Graph</h1><p>{bridgeError}</p></main>
  if (!graph || !model) return <main className="status-panel"><ThemeToggle theme={theme} onToggle={toggleTheme} /><h1>VPS Graph</h1><p>Preparing local infrastructure explorer…</p></main>
  if (!connected) return <><main className="connect-screen"><ThemeToggle theme={theme} onToggle={toggleTheme} /><section><span className="connect-screen__mark"><img className="connect-screen__logo" src={brandLogo} alt="" /></span><h1>Connect to your VPS</h1><p>VPS Graph uses your existing public-key SSH setup for local, read-only discovery. It never accepts an unknown server key or installs anything remotely.</p>{connectionForm}{scanIssue && <ConnectionIssueCard issue={scanIssue} onOpenGuide={setSetupTopic} />}<ConnectionOrientation firstRun={firstRun} onOpenGuide={setSetupTopic} /></section></main>{setupTopic && <SetupGuide topic={setupTopic} onClose={() => setSetupTopic(null)} />}</>

  const hostMetadata = model.host ? safeMetadata(model.host) : {}
  const dockerStatus = model.engine ? safeMetadata(model.engine)['discovery status'] ?? model.engine.label : 'Docker not discovered'
  return <main className="explorer-app" data-explorer-open={explorerOpen} data-inspector-open={inspectorOpen}>
    <header className="topbar">
      <span className="topbar__brand"><img className="topbar__brand-logo" src={brandLogo} alt="" /><span>VPS Graph</span></span><span className="topbar__host">{model.host?.label ?? host}</span><span className="topbar__status">{active ? 'Rescanning · previous data remains available' : scanState === 'ERROR' ? 'Latest scan failed · previous data retained' : `Connected · ${dockerStatus}${scanFeedback ? ` · Scan complete · ${scanFeedback}` : ''}`}</span>
      <button type="button" className="topbar__search" onClick={() => { setSearchOpen(true); window.requestAnimationFrame(() => searchRef.current?.focus()) }}><Search size={15} /> Search infrastructure <kbd>Ctrl K</kbd></button>
      <button type="button" className="quiet-button" onClick={() => requestScan()} disabled={active}>{active ? 'Scanning…' : 'Rescan'}</button>
      <ThemeToggle theme={theme} onToggle={toggleTheme} />
      <button type="button" className="icon-button" onClick={() => setConnectionSettingsOpen(true)} aria-label="Connection settings" title="Connection settings"><Settings2 size={16} /></button>
    </header>
    <nav className="primary-nav" aria-label="Primary navigation">
      {([['OVERVIEW', LayoutPanelLeft], ['APPLICATIONS', Boxes], ['SERVICES', Activity], ['ROUTING', Globe2], ['NETWORK', Share2], ['STORAGE', Archive], ['CHANGES', History]] as const).map(([name, Icon]) => <button key={name} type="button" aria-current={section === name ? 'page' : undefined} className={section === name ? 'is-active' : ''} onClick={() => { if (name === 'CHANGES') { openChanges(); return } setSelectedChange(null); setSection(name); setNetworkSubsection(null); setTopologyOpen(false); setFocusedNodeId(null); if (window.innerWidth <= 820) { setExplorerOpen(false); setInspectorOpen(false) } }}><Icon size={15} /> {name[0] + name.slice(1).toLowerCase()}</button>)}
      <button type="button" className="nav-panel-toggle" onClick={toggleExplorer} aria-label="Toggle resource explorer">{explorerOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}</button>
      <button type="button" className="nav-panel-toggle" onClick={() => setInspectorOpen((open) => !open)} aria-label="Toggle details inspector">{inspectorOpen ? <PanelRightClose size={16} /> : <PanelRightOpen size={16} />}</button>
    </nav>
    <section className="workspace-shell">
      <aside className="resource-explorer" aria-label="Resource Explorer">
        <header><strong>Resource Explorer</strong><button type="button" onClick={() => setExplorerOpen(false)} aria-label="Collapse resource explorer"><PanelLeftClose size={15} /></button></header>
        {model.host && <button type="button" className={`explorer-host ${selectedId === model.host.id ? 'is-selected' : ''}`} onClick={() => selectResource(model.host!)}><Server size={15} /><span><small>Host</small>{model.host.label}</span></button>}
        <button type="button" className={`explorer-resource explorer-changes ${section === 'CHANGES' ? 'is-selected' : ''}`} aria-current={section === 'CHANGES' ? 'location' : undefined} onClick={openChanges}><History size={14} /><span>Changes</span>{changesCount > 0 && <small>{changesCount}</small>}</button>
        <ExplorerSection title="Applications" count={model.applications.length} open={!collapsedExplorer.has('applications')} onToggle={() => toggleExplorerItem('applications')}>
          {model.applications.map((application) => <div className={`explorer-project ${application.containers.some((container) => container.id === selectedId) ? 'has-selected-child' : ''}`} key={application.project.id}>
            <button type="button" aria-expanded={!collapsedExplorer.has(application.project.id)} className={`explorer-parent ${selectedId === application.project.id ? 'is-selected' : ''}`} onClick={() => { selectResource(application.project); toggleExplorerItem(application.project.id) }}>{collapsedExplorer.has(application.project.id) ? <ChevronRight className="tree-chevron" size={14} /> : <ChevronDown className="tree-chevron" size={14} />}<Boxes className="tree-parent-icon" size={14} /><span>{application.project.label}</span><small>{application.containers.length}</small></button>
            {!collapsedExplorer.has(application.project.id) && application.containers.map((container) => <button type="button" className={`explorer-child ${selectedId === container.id ? 'is-selected' : ''}`} key={container.id} onClick={() => selectResource(container)}><Box size={12} /><span>{container.label}</span><StateLabel value={containerState(container)} /></button>)}
          </div>)}
          {model.standaloneContainers.length > 0 && <div className="explorer-project"><p>Standalone</p>{model.standaloneContainers.map((container) => <button type="button" className={`explorer-child ${selectedId === container.id ? 'is-selected' : ''}`} key={container.id} onClick={() => selectResource(container)}><Box size={13} /><span>{container.label}</span><StateLabel value={containerState(container)} /></button>)}</div>}
        </ExplorerSection>
        <ExplorerSection title="Host services" count={model.services.length} open={!collapsedExplorer.has('services')} onToggle={() => toggleExplorerItem('services')}>
          <button type="button" className={`explorer-resource ${section === 'SERVICES' ? 'is-selected' : ''}`} aria-current={section === 'SERVICES' ? 'location' : undefined} onClick={() => { setSection('SERVICES'); setSelectedId(model.services[0]?.id ?? null); setInspectorOpen(Boolean(model.services[0])) }}><Activity size={14} /><span>Systemd services</span><small>{model.services.length}</small></button>
        </ExplorerSection>
        <ExplorerSection title="Routing" count={model.domains.length} open={!collapsedExplorer.has('routing')} onToggle={() => toggleExplorerItem('routing')}>
          {model.domains.map((domain) => <button type="button" className={`explorer-resource ${selectedId === domain.id ? 'is-selected' : ''}`} key={domain.id} onClick={() => selectResource(domain)}><Globe2 size={13} /><span>{domain.label}</span><small>{safeMetadata(domain).resolution?.toLowerCase()}</small></button>)}
          {model.domains.length === 0 && <p className="explorer-empty">No domains</p>}
        </ExplorerSection>
        <ExplorerSection title="Infrastructure" open={!collapsedExplorer.has('infrastructure')} onToggle={() => toggleExplorerItem('infrastructure')}>
          <button type="button" className={`explorer-resource ${section === 'NETWORK' && networkSubsection === 'LISTENERS' ? 'is-selected' : ''}`} aria-current={section === 'NETWORK' && networkSubsection === 'LISTENERS' ? 'location' : undefined} onClick={() => openNetworkSubsection('LISTENERS')}><RadioTower size={14} /><span>Host listeners</span><small>{model.listeners.length}</small></button><button type="button" className={`explorer-resource ${section === 'NETWORK' && networkSubsection === 'NETWORKS' ? 'is-selected' : ''}`} aria-current={section === 'NETWORK' && networkSubsection === 'NETWORKS' ? 'location' : undefined} onClick={() => openNetworkSubsection('NETWORKS')}><Network size={14} /><span>Networks</span><small>{model.networks.length}</small></button><button type="button" className={`explorer-resource ${section === 'NETWORK' && networkSubsection === 'PORTS' ? 'is-selected' : ''}`} aria-current={section === 'NETWORK' && networkSubsection === 'PORTS' ? 'location' : undefined} onClick={() => openNetworkSubsection('PORTS')}><Waypoints size={14} /><span>Ports</span><small>{model.ports.length}</small></button><button type="button" className={`explorer-resource ${section === 'STORAGE' ? 'is-selected' : ''}`} aria-current={section === 'STORAGE' ? 'location' : undefined} onClick={() => model.mounts[0] ? selectResource(model.mounts[0]) : setSection('STORAGE')}><Archive size={14} /><span>Mounts</span><small>{model.mounts.length}</small></button>
        </ExplorerSection>
      </aside>
      <button type="button" className="shell-scrim" onClick={() => { setExplorerOpen(false); setInspectorOpen(false) }} aria-label="Close open panel" />
      <section className="workspace" aria-label={`${section[0] + section.slice(1).toLowerCase()} workspace`}>
        {!widthHintDismissed && <aside className="width-hint">For the best overview, widen the VPS Graph Tool Window.<button type="button" onClick={dismissWidthHint} aria-label="Dismiss width guidance"><X size={13} /></button></aside>}
        {scanState === 'ERROR' && scanIssue && <div className="workspace-notice"><ConnectionIssueCard issue={scanIssue} onOpenGuide={setSetupTopic} /><p>Previous scan data and history remain available.</p></div>}
        {section === 'OVERVIEW' && <Overview model={model} hostMetadata={hostMetadata} selectedId={selectedId} changes={changesPayload} onSelect={selectResource} onOpenChanges={openChanges} onOpenGuide={setSetupTopic} />}
        {section === 'APPLICATIONS' && (topologyOpen ? <Topology flow={topologyFlow} onSelect={selectTopologyResource} onReady={(instance) => { flowRef.current = instance; fitTopology() }} onExit={() => { setTopologyOpen(false); setFocusedNodeId(null) }} /> : <Applications model={model} selectedId={selectedId} onSelect={selectResource} onTopology={() => setTopologyOpen(true)} />)}
        {section === 'SERVICES' && <ServicesPage model={model} selectedId={selectedId} onSelect={selectResource} />}
        {section === 'ROUTING' && <RoutingPage model={model} selectedId={selectedId} onSelect={selectResource} />}
        {section === 'NETWORK' && <NetworkPage model={model} selectedId={selectedId} subsection={networkSubsection} onSelect={selectResource} />}
        {section === 'STORAGE' && <StoragePage model={model} selectedId={selectedId} onSelect={selectResource} />}
        {section === 'CHANGES' && (changesPayload ? <ChangesErrorBoundary resetKey={`${changesPayload.comparison.current.snapshotId}:${changesPayload.diff.previousSnapshotId ?? ''}:${changesPayload.diff.status}`}><ChangesPage payload={changesPayload} loading={changesLoading} error={changesError} onCompare={compareSnapshots} onInspect={inspectChange} onNavigate={navigateFromChange} /></ChangesErrorBoundary> : <div className="page"><header className="page-heading"><div><p>Changes</p><h1>{changesLoading ? 'Loading local history…' : 'Change history unavailable'}</h1><span>{changesError ?? 'Scan this server to create a baseline.'}</span></div></header></div>)}
      </section>
      {inspectorOpen && (section === 'CHANGES' && changesPayload ? <ChangeInspector selection={selectedChange} payload={changesPayload} onClose={() => setInspectorOpen(false)} onNavigate={navigateFromChange} /> : <Inspector node={selected} model={model} onClose={() => setInspectorOpen(false)} onSelect={selectResource} onTopology={revealTopology} />)}
    </section>
    {connectionSettingsOpen && <div className="modal-backdrop" role="presentation"><section className="connection-modal" role="dialog" aria-modal="true" aria-label="Connection settings"><header><div><h2>Connection settings</h2><p>Private-key contents and passphrases are never stored.</p></div><button type="button" className="icon-button" onClick={() => setConnectionSettingsOpen(false)} aria-label="Close connection settings"><X size={16} /></button></header>{connectionForm}{scanIssue && <ConnectionIssueCard issue={scanIssue} onOpenGuide={setSetupTopic} />}<ConnectionOrientation firstRun={false} onOpenGuide={setSetupTopic} /><footer className="connection-modal__footer"><button type="button" className="quiet-button" onClick={disconnect} disabled={!canDisconnect(scanState)}><LogOut size={15} /> Disconnect</button><span>{active ? 'Disconnect is available when the scan completes.' : 'Returns to Connect without deleting remembered fields or local history.'}</span></footer></section></div>}
    {searchOpen && <div className="search-overlay" role="dialog" aria-modal="true" aria-label="Search infrastructure"><div className="search-dialog"><Search size={17} /><input ref={searchRef} value={searchQuery} onChange={(event) => { setSearchQuery(event.target.value); setSearchIndex(0) }} placeholder="Find domains, containers, services, listeners…" /><button type="button" onClick={() => { setSearchOpen(false); setSearchQuery('') }} aria-label="Close search"><X size={16} /></button><div className="search-dialog__results">{searchQuery && (searchResults.length ? searchResults.map((node, index) => <button type="button" key={node.id} className={index === searchIndex ? 'is-active' : ''} onMouseEnter={() => setSearchIndex(index)} onClick={() => selectResource(node)}><span><strong>{node.label}</strong><small>{node.type.replaceAll('_', ' ').toLowerCase()} · {resourceContext(node, model)}</small></span><ChevronRight size={15} /></button>) : <p>No matching safe infrastructure metadata.</p>)}</div><footer><kbd>↑↓</kbd> Navigate <kbd>Enter</kbd> Open <kbd>Esc</kbd> Close</footer></div></div>}
    {setupTopic && <SetupGuide topic={setupTopic} onClose={() => setSetupTopic(null)} />}
  </main>
}

function ExplorerSection({ title, count, open, onToggle, children }: { title: string; count?: number; open: boolean; onToggle: () => void; children: ReactNode }) {
  return <section className="explorer-section"><button type="button" className="explorer-section__title" onClick={onToggle}>{open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<span>{title}</span>{count !== undefined && <small>{count}</small>}</button>{open && children}</section>
}

function Overview({ model, hostMetadata, selectedId, changes, onSelect, onOpenChanges, onOpenGuide }: { model: ReturnType<typeof resourceModel>; hostMetadata: Record<string, string>; selectedId: string | null; changes: ChangesPayload | null; onSelect: (node: InfraNode) => void; onOpenChanges: () => void; onOpenGuide: (topic: SetupTopic) => void }) {
  const changeSummary = changes ? overviewChangeSummary(changes) : null
  const engineMetadata = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })
  return <div className="page overview-page"><header className="page-heading"><div><p>Host overview</p><h1>{model.host?.label}</h1><span>{hostMetadata['operating system'] ?? hostMetadata.os ?? 'Linux'}{hostMetadata['os version'] ? `, ${hostMetadata['os version']}` : ''}</span></div><div className="host-engine"><Database size={17} /> {safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['docker version'] ? `Docker ${safeMetadata(model.engine!)['docker version']}` : safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['discovery status'] ?? 'Docker unavailable'}</div></header>
    <dl className="overview-counts"><div><dt>Applications</dt><dd>{model.applications.length}</dd></div><div><dt>Containers</dt><dd>{model.containers.length}</dd></div><div><dt>Host services</dt><dd>{listeningServices(model).length}<small> listening</small></dd></div><div><dt>Domains</dt><dd>{model.domains.length}</dd></div><div><dt>Routes</dt><dd>{model.routes.length}</dd></div></dl>
    <HelperStatus dockerState={engineMetadata['docker discovery state']} caddyState={engineMetadata['caddy discovery state']} systemdState={hostMetadata['systemd discovery status']} listenerState={hostMetadata['listener discovery status']} onOpenGuide={onOpenGuide} />
    {model.routes.length > 0 && <p className="routing-summary"><Globe2 size={14} /> {countLabel(model.domains.length, 'domain')} · {countLabel(model.routes.filter((route) => safeMetadata(route.upstream).resolution === 'RESOLVED').length, 'resolved route')} · {countLabel(model.routes.filter((route) => safeMetadata(route.upstream).resolution === 'HOST_TARGET').length, 'host upstream')}</p>}
    {changeSummary && <button type="button" className="overview-changes" onClick={onOpenChanges}><History size={15} /><span><strong>{changeSummary.title}</strong><small>{changeSummary.detail}</small></span><ChevronRight size={15} /></button>}
    <section className="overview-applications"><header><h2>What is running</h2><span>{countLabel(model.applications.length, 'application')}</span></header><div>{model.applications.length ? model.applications.map((application) => <button type="button" aria-pressed={selectedId === application.project.id} className={selectedId === application.project.id ? 'is-selected' : ''} key={application.project.id} onClick={() => onSelect(application.project)}><span><strong>{application.project.label}</strong><small>{countLabel(application.containers.length, 'container')}</small></span><StateLabel value={application.status} /><ChevronRight size={16} /></button>) : <p className="empty-state">{dockerEmptyCopy(engineMetadata['docker discovery state'])}</p>}</div></section>
  </div>
}

function Applications({ model, selectedId, onSelect, onTopology }: { model: ReturnType<typeof resourceModel>; selectedId: string | null; onSelect: (node: InfraNode) => void; onTopology: () => void }) {
  const dockerState = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['docker discovery state']
  return <div className="page applications-page"><header className="page-heading"><div><p>Applications</p><h1>Compose projects and services</h1><span>Review each project with its discovered services.</span></div><button type="button" className="quiet-button" onClick={onTopology}><Waypoints size={15} /> Show topology</button></header><div className="application-groups">{model.applications.map((application) => { const metadata = safeMetadata(application.project); const childSelected = application.containers.some((container) => container.id === selectedId); return <section key={application.project.id} className={`application-group ${selectedId === application.project.id ? 'is-selected' : ''} ${childSelected ? 'has-selected-service' : ''}`}><header><button type="button" aria-pressed={selectedId === application.project.id} onClick={() => onSelect(application.project)}><Boxes size={17} /><span><strong>{application.project.label}</strong><small>{compactPath(metadata['config files'])} · {countLabel(application.containers.length, 'container')}</small></span></button><StateLabel value={application.status} /></header><div className="service-grid">{application.containers.map((container) => <ServiceCard key={container.id} node={container} selected={selectedId === container.id} routedCount={routedDomains(container, model).length} onSelect={onSelect} />)}</div></section> })}{model.standaloneContainers.length > 0 && <section className="application-group"><header><span><strong>Standalone containers</strong><small>Not assigned to a Compose project</small></span></header><div className="service-grid">{model.standaloneContainers.map((container) => <ServiceCard key={container.id} node={container} selected={selectedId === container.id} routedCount={routedDomains(container, model).length} onSelect={onSelect} />)}</div></section>}{model.applications.length === 0 && model.standaloneContainers.length === 0 && <p className="empty-state">{dockerEmptyCopy(dockerState)}</p>}</div></div>
}

function ServicesPage({ model, selectedId, onSelect }: { model: ReturnType<typeof resourceModel>; selectedId: string | null; onSelect: (node: InfraNode) => void }) {
  const [filter, setFilter] = useState<ServiceFilter>('LISTENING')
  const services = servicesForFilter(model, filter)
  const discovery = safeMetadata(model.host ?? { id: '', label: '', type: 'SERVER', metadata: {} })['systemd discovery status']
  const dockerState = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['docker discovery state']
  const discoveryCopy = dockerState !== 'AVAILABLE' ? { label: 'Unavailable', message: "Host service discovery isn't available until the optional server helper completes successfully." } : discoveryStatusCopy('Systemd service', discovery)
  return <div className="page services-page"><header className="page-heading"><div><p>Services</p><h1>Systemd services</h1><span>Read-only unit state and listener ownership discovered on the host.</span></div>{discovery && <span className="routing-page__status">{discoveryCopy.label}</span>}</header>
    <div className="service-filters" aria-label="Filter services">{(['LISTENING', 'ACTIVE', 'ALL'] as const).map((value) => <button type="button" aria-pressed={filter === value} className={filter === value ? 'is-active' : ''} key={value} onClick={() => setFilter(value)}>{value[0] + value.slice(1).toLowerCase()}</button>)}</div>
    {discoveryCopy.message && services.length > 0 && <p className="discovery-note">{discoveryCopy.message}</p>}
    <div className="host-service-list">{services.map((service) => { const metadata = safeMetadata(service); const endpoints = relatedResources(service, model, 'LISTENS_ON').filter((node) => node.type === 'HOST_LISTENER'); return <button type="button" aria-pressed={selectedId === service.id} className={selectedId === service.id ? 'is-selected' : ''} key={service.id} onClick={() => onSelect(service)}><Activity size={15} /><span><strong>{service.label}</strong><small>{metadata.description ?? metadata['service type'] ?? 'Systemd service'}</small>{endpoints.length > 0 && <small title={endpoints.map((node) => node.label).join(' · ')}>{endpoints.map((node) => node.label).join(' · ')}</small>}</span><StateLabel value={metadata['active state'] === 'active' ? 'running' : metadata['active state'] ?? 'unknown'} /><em>{countLabel(Number(metadata['listener count'] ?? 0), 'listener')}</em><ChevronRight size={15} /></button> })}{services.length === 0 && <p className="empty-state">{discoveryCopy.message ?? `No services match the ${filter.toLowerCase()} filter. Try another filter.`}</p>}</div>
  </div>
}

function RoutingPage({ model, selectedId, onSelect }: { model: ReturnType<typeof resourceModel>; selectedId: string | null; onSelect: (node: InfraNode) => void }) {
  const engineMetadata = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })
  const caddyStatus = engineMetadata['caddy discovery status']
  return <div className="page routing-page"><header className="page-heading"><div><p>Routing</p><h1>Domains to services</h1><span>Sanitized Caddy routes; no DNS, HTTP, or health probing is performed.</span></div>{caddyStatus && <span className="routing-page__status">{caddyStatus}</span>}</header>
    <div className="routing-lane-headings" aria-hidden="true"><span>Domains</span><span>Reverse proxy</span><span>Upstreams / services</span></div>
    <div className="routing-list">{model.routes.map((route) => { const metadata = safeMetadata(route.upstream); const resolution = metadata.resolution ?? 'UNRESOLVED'; const hostResolution = metadata['host resolution']; const targetLabel = route.target?.label ?? route.hostService?.label ?? route.hostListeners[0]?.label ?? metadata.dial ?? route.upstream.label; const targetDetail = route.target ? metadata['container state'] ?? 'state unknown' : route.hostService ? `${countLabel(route.hostListeners.length, 'listener')} · ${safeMetadata(route.hostService)['active state'] ?? 'state unknown'}` : hostResolution ? metadata['host resolution reason'] ?? hostResolution.replaceAll('_', ' ').toLowerCase() : resolution.replaceAll('_', ' ').toLowerCase(); return <article className={`route-row route-row--${resolution.toLowerCase()}`} key={route.upstream.id}>
      <div className="route-row__domains">{route.domains.length ? route.domains.map((domain) => <button type="button" className={selectedId === domain.id ? 'is-selected' : ''} key={domain.id} onClick={() => onSelect(domain)}><Globe2 size={14} /><span><strong>{domain.label}</strong>{safeMetadata(domain).paths && <small>{safeMetadata(domain).paths}</small>}</span></button>) : <span className="route-row__catchall">Catch-all route</span>}</div>
      <ArrowRight className="route-row__arrow" size={16} />
      <button type="button" className={`route-row__proxy ${selectedId === route.proxy?.id ? 'is-selected' : ''}`} onClick={() => route.proxy && onSelect(route.proxy)} disabled={!route.proxy}><Share2 size={14} /><span>{route.proxy?.label ?? 'Caddy'}</span></button>
      <ArrowRight className="route-row__arrow" size={16} />
      <button type="button" className={`route-row__target ${selectedId === route.upstream.id ? 'is-selected' : ''}`} onClick={() => onSelect(route.upstream)}><span><strong>{targetLabel}</strong><small>{targetDetail}</small></span><em>{(hostResolution ?? resolution).replaceAll('_', ' ')}</em></button>
    </article> })}{model.routes.length === 0 && <p className="empty-state">{caddyEmptyCopy(engineMetadata['docker discovery state'], engineMetadata['caddy discovery state'])}</p>}</div>
  </div>
}

function NetworkPage({ model, selectedId, subsection, onSelect }: { model: ReturnType<typeof resourceModel>; selectedId: string | null; subsection: NetworkSubsection | null; onSelect: (node: InfraNode) => void }) {
  const listenersRef = useRef<HTMLElement>(null)
  const networksRef = useRef<HTMLElement>(null)
  const portsRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const target = subsection === 'LISTENERS' ? listenersRef.current : subsection === 'NETWORKS' ? networksRef.current : subsection === 'PORTS' ? portsRef.current : null
    if (target) window.requestAnimationFrame(() => {
      const workspace = target.closest('.workspace') as HTMLElement | null
      if (!workspace) return
      const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
      if (window.innerWidth > 1100) { workspace.scrollTo({ top: 0, behavior }); return }
      const targetRect = target.getBoundingClientRect()
      const workspaceRect = workspace.getBoundingClientRect()
      if (targetRect.top < workspaceRect.top + 8 || targetRect.top > workspaceRect.bottom - 80) workspace.scrollTo({ top: workspace.scrollTop + targetRect.top - workspaceRect.top - 16, behavior })
    })
  }, [subsection])
  const focusLabel = subsection === 'LISTENERS' ? 'Host listeners' : subsection === 'NETWORKS' ? 'Docker networks' : subsection === 'PORTS' ? 'Published ports' : null
  const listenerStatus = safeMetadata(model.host ?? { id: '', label: '', type: 'SERVER', metadata: {} })['listener discovery status']
  const dockerState = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['docker discovery state']
  const listenerCopy = dockerState !== 'AVAILABLE' ? { label: 'Unavailable', message: 'Host listener discovery is unavailable until the optional server helper completes successfully.' } : discoveryStatusCopy('host listener', listenerStatus)
  return <div className="page network-page"><header className="page-heading"><div><p>Network</p><h1>Service relationships</h1><span aria-live="polite">{focusLabel ? `Focused on ${focusLabel} from Resource Explorer.` : 'Host listeners, Docker networks, and published ports arranged by responsibility.'}</span></div>{listenerStatus && listenerCopy.label !== 'Available' && <span className="routing-page__status">{listenerCopy.label}</span>}</header><div className="network-lanes"><section><header className="network-lane__heading"><h2>Container services</h2><span>{model.containers.length}</span></header>{model.containers.map((container) => <button className={selectedId === container.id ? 'is-selected' : ''} aria-pressed={selectedId === container.id} key={container.id} type="button" onClick={() => onSelect(container)}><Box size={14} /><span>{container.label}<small>{safeMetadata(container)['compose service'] ?? resourceContext(container, model)}</small></span><StateLabel value={containerState(container)} /></button>)}{model.containers.length === 0 && <p className="empty-state">{dockerEmptyCopy(dockerState)}</p>}</section><section ref={listenersRef} className={subsection === 'LISTENERS' ? 'is-focused' : ''} aria-label="Host listeners"><header className="network-lane__heading"><h2>Host listeners</h2><span>{model.listeners.length}</span></header>{subsection === 'LISTENERS' && <p className="network-lane__focus">Explorer focus</p>}{model.listeners.map((listener) => { const metadata = safeMetadata(listener); const correlated = publishedPorts(listener, model); return <button className={selectedId === listener.id ? 'is-selected' : ''} aria-pressed={selectedId === listener.id} key={listener.id} type="button" onClick={() => onSelect(listener)}><RadioTower size={14} /><span>{listener.label}<small>{metadata['systemd unit'] ?? metadata['ownership state']?.toLowerCase() ?? 'unresolved'}{correlated.length ? ` · ${countLabel(correlated.length, 'published port')}` : ''}</small></span></button> })}{model.listeners.length === 0 && <p className="empty-state">{listenerCopy.message ?? 'No host listeners detected.'}</p>}</section><section ref={networksRef} className={subsection === 'NETWORKS' ? 'is-focused' : ''} aria-label="Docker networks"><header className="network-lane__heading"><h2>Docker networks</h2><span>{model.networks.length}</span></header>{subsection === 'NETWORKS' && <p className="network-lane__focus">Explorer focus</p>}{model.networks.map((network) => <button className={selectedId === network.id ? 'is-selected' : ''} aria-pressed={selectedId === network.id} key={network.id} type="button" onClick={() => onSelect(network)}><Share2 size={14} /><span>{network.label}<small title={connectedContainers(network, model).map((container) => container.label).join(' · ')}>{connectedContainers(network, model).map((container) => container.label).join(' · ') || 'No containers'}</small></span><em>{connectedContainers(network, model).length}</em></button>)}{model.networks.length === 0 && <p className="empty-state">{dockerEmptyCopy(dockerState, 'No Docker networks detected.')}</p>}</section><section ref={portsRef} className={subsection === 'PORTS' ? 'is-focused' : ''} aria-label="Published ports"><header className="network-lane__heading"><h2>Published ports</h2><span>{model.ports.length}</span></header>{subsection === 'PORTS' && <p className="network-lane__focus">Explorer focus</p>}{model.ports.map((port) => { const metadata = safeMetadata(port); return <button className={selectedId === port.id ? 'is-selected' : ''} aria-pressed={selectedId === port.id} key={port.id} type="button" onClick={() => onSelect(port)}><Waypoints size={14} /><span>{port.label}<small>{metadata.exposure === 'published' ? `Published · ${metadata['host binding']}` : 'Internal only'}</small></span></button> })}{model.ports.length === 0 && <p className="empty-state">{dockerEmptyCopy(dockerState, 'No published Docker ports detected.')}</p>}</section></div></div>
}

function StoragePage({ model, selectedId, onSelect }: { model: ReturnType<typeof resourceModel>; selectedId: string | null; onSelect: (node: InfraNode) => void }) {
  const dockerState = safeMetadata(model.engine ?? { id: '', label: '', type: 'DOCKER_ENGINE', metadata: {} })['docker discovery state']
  return <div className="page storage-page"><header className="page-heading"><div><p>Storage</p><h1>Container mounts</h1><span>Mounts are grouped by service; full paths stay in details.</span></div></header><div className="storage-list">{model.containers.map((container) => { const mounts = relatedResources(container, model, 'MOUNTS'); const childSelected = mounts.some((mount) => mount.id === selectedId); return <section key={container.id} className={`${selectedId === container.id ? 'is-selected' : ''} ${childSelected ? 'has-selected-mount' : ''}`}><header><button type="button" onClick={() => onSelect(container)}><Box size={15} /> {container.label}</button><span>{mounts.length ? countLabel(mounts.length, 'mount') : 'No mounts'}</span></header>{mounts.map((mount) => { const metadata = safeMetadata(mount); const path = `${metadata.source ?? 'Not available'} → ${metadata.destination ?? 'Not available'}`; return <button type="button" aria-pressed={selectedId === mount.id} key={mount.id} className={`mount-row ${selectedId === mount.id ? 'is-selected' : ''}`} onClick={() => onSelect(mount)}><Archive size={15} /><span><strong>{compactPath(metadata['volume name'] ?? metadata.source)}</strong><small title={path}>{compactPath(metadata.source)} → {compactPath(metadata.destination)}</small></span><em>{metadata.type} · {metadata.access}</em><ChevronRight size={15} /></button>})}</section> })}{model.containers.length === 0 && <p className="empty-state">{dockerEmptyCopy(dockerState, 'No container mounts detected.')}</p>}</div></div>
}

function Topology({ flow, onSelect, onReady, onExit }: { flow: { nodes: FlowNode[]; edges: Edge[] }; onSelect: (node: InfraNode) => void; onReady: (instance: ReactFlowInstance<FlowNode, Edge>) => void; onExit: () => void }) {
  return <div className="topology-page"><header className="page-heading"><div><p>Applications</p><h1>Topology</h1><span>Relationship view for services and applications only.</span></div><button type="button" className="quiet-button" onClick={onExit}>Exit topology</button></header><div className="topology-canvas"><ReactFlow<FlowNode, Edge> nodes={flow.nodes} edges={flow.edges} nodeTypes={topologyNodeTypes} proOptions={{ hideAttribution: true }} minZoom={0.3} onInit={onReady} onNodeClick={(_, node) => onSelect(node.data)}><Background gap={24} size={1} /><Controls showInteractive={false} /></ReactFlow></div></div>
}

function Inspector({ node, model, onClose, onSelect, onTopology }: { node: InfraNode | null; model: ReturnType<typeof resourceModel>; onClose: () => void; onSelect: (node: InfraNode) => void; onTopology: () => void }) {
  if (!node) return <aside className="inspector"><header><span>Details</span><button type="button" onClick={onClose} aria-label="Close details"><X size={16} /></button></header><p className="inspector-empty">Select a resource from the explorer, workspace, or search.</p></aside>
  const metadata = safeMetadata(node)
  const parent = model.parentByChild.get(node.id) ? model.byId.get(model.parentByChild.get(node.id)!) : undefined
  const sections = inspectorSections(node, metadata)
  const canShowTopology = ['SERVER', 'DOCKER_ENGINE', 'DOCKER_COMPOSE_PROJECT', 'DOCKER_CONTAINER'].includes(node.type)
  return <aside className="inspector"><header><span>Details</span><button type="button" onClick={onClose} aria-label="Close details"><X size={16} /></button></header><div className="inspector-title"><h2>{node.label}</h2><p>{node.type.replaceAll('_', ' ').toLowerCase()}</p>{node.type === 'DOCKER_CONTAINER' && <StateLabel value={containerState(node)} />}</div><div className="inspector-actions">{canShowTopology && <button type="button" onClick={onTopology}><Waypoints size={14} /> Show topology</button>}{parent && <button type="button" onClick={() => onSelect(parent)}>Reveal parent</button>}</div>{sections.map(([title, entries]) => entries.length > 0 && <section key={title} className="inspector-section"><h3>{title}</h3><dl>{entries.map(([key, value]) => <div key={key}><dt>{key}</dt><dd className={isPathMetadata(key) ? 'inspector-value--path' : undefined}>{value}</dd></div>)}</dl></section>)}</aside>
}

function isPathMetadata(key: string): boolean {
  return ['config files', 'working directory', 'source', 'destination', 'mounts'].includes(key)
}

function inspectorSections(node: InfraNode, metadata: Record<string, string>): [string, [string, string][]][] {
  const pick = (...keys: string[]) => keys.flatMap((key) => metadata[key] ? [[key, metadata[key]] as [string, string]] : [])
  if (node.type === 'DOCKER_CONTAINER') return [['Runtime', pick('state', 'image', 'restart policy', 'container id')], ['Application', pick('compose project', 'compose service')], ['Network', pick('ports', 'networks')], ['Storage', pick('mounts')]]
  if (node.type === 'DOCKER_COMPOSE_PROJECT') return [['Application', pick('project', 'container count', 'config files', 'working directory')]]
  if (node.type === 'MOUNT') return [['Storage', pick('type', 'volume name', 'source', 'destination', 'access')]]
  if (node.type === 'PORT') return [['Network', pick('container port', 'protocol', 'host binding', 'exposure')]]
  if (node.type === 'NETWORK') return [['Network', pick('network', 'connected containers')]]
  if (node.type === 'DOMAIN') return [['Route', pick('domain', 'paths', 'resolution', 'upstreams', 'caddy instance', 'resolved containers', 'resolved host services', 'route count')]]
  if (node.type === 'UPSTREAM') return [['Route', pick('dial', 'resolution', 'paths', 'domains', 'backend set size')], ['Resolution', pick('host resolution', 'host service', 'eligible listeners', 'candidate services', 'host resolution reason', 'matching port', 'matching reason', 'shared network', 'container', 'container state', 'compose project', 'compose service')]]
  if (node.type === 'SYSTEMD_SERVICE') return [['Service', pick('unit', 'description', 'load state', 'active state', 'sub state', 'unit file state', 'service type', 'user', 'group', 'listener count', 'discovery status')], ['Technical', pick('control group')]]
  if (node.type === 'HOST_LISTENER') return [['Listener', pick('bind', 'protocol', 'local address', 'port', 'wildcard', 'loopback', 'ownership state', 'systemd unit', 'process name', 'discovery status')]]
  if (node.type === 'REVERSE_PROXY') return [['Caddy', pick('proxy', 'container name', 'discovery status', 'route count', 'domain count', 'image')]]
  if (node.type === 'SERVER') return [['Host', pick('hostname', 'operating system', 'os version', 'kernel', 'architecture', 'ssh host', 'ssh username', 'os')], ['Discovery', pick('systemd discovery status', 'listener discovery status', 'systemd status reasons', 'listener status reasons')]]
  return [['Details', Object.entries(metadata)]]
}
