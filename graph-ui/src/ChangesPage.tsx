import { ArrowRight, CircleHelp, Clock3, GitCompareArrows, History, Minus, Plus, RefreshCw, Search, X } from 'lucide-react'
import { Component, useMemo, useState, type ErrorInfo, type ReactNode } from 'react'
import { comparisonCandidates, evidenceLabel, exactLocalTime, fieldLabel, filterAllows, formatLocalTime, nodeChangeGroups, relationLabel, relationshipPresentations, resourceTypeLabel, stateCopy, uncertainRelationshipPresentations, uncertaintyCopy, type ChangeFilter, type RelationshipPresentation } from './changes-model'
import type { ChangeResourceSnapshot, ChangesPayload, DiffUncertainty, NodeChange } from './types'

export type ChangeSelection =
  | { kind: 'NODE'; change: NodeChange; resource: ChangeResourceSnapshot | null }
  | { kind: 'RELATIONSHIP'; relationship: RelationshipPresentation }
  | { kind: 'UNCERTAINTY'; uncertainty: DiffUncertainty }

interface ChangesPageProps {
  payload: ChangesPayload
  loading: boolean
  error: string | null
  onCompare: (previousSnapshotId: string, currentSnapshotId: string) => void
  onInspect: (selection: ChangeSelection) => void
  onNavigate: (nodeId: string) => void
}

export class ChangesErrorBoundary extends Component<{ resetKey: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() { return { failed: true } }

  componentDidCatch(_error: Error, _info: ErrorInfo) {
    // Safe sandbox diagnostic: never include payload values, stack traces, or local paths.
    console.error('VPS Graph Changes workspace render failed', { category: 'render-error', operation: 'changes-workspace' })
  }

  componentDidUpdate(previous: Readonly<{ resetKey: string }>) {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) this.setState({ failed: false })
  }

  render() {
    if (!this.state.failed) return this.props.children
    return <div className="page changes-page"><header className="page-heading"><div><p>Changes</p><h1>Changes could not be displayed.</h1><span>Your live infrastructure views remain available. Retry after reopening Changes.</span></div></header><section className="changes-state"><span><CircleHelp size={21} /></span><div><strong>Changes could not be displayed.</strong><p>The local comparison remains unchanged. No infrastructure action was taken.</p><button type="button" className="quiet-button" onClick={() => this.setState({ failed: false })}>Retry Changes</button></div></section></div>
  }
}

export function ChangesPage({ payload, loading, error, onCompare, onInspect, onNavigate }: ChangesPageProps) {
  const [filter, setFilter] = useState<ChangeFilter>('ALL')
  const [query, setQuery] = useState('')
  const resources = useMemo(() => new Map(payload.resources.map((resource) => [resource.nodeId, resource])), [payload.resources])
  const relationships = useMemo(() => relationshipPresentations(payload), [payload])
  const uncertainRelationships = useMemo(() => uncertainRelationshipPresentations(payload), [payload])
  const summary = payload.diff.summary
  const groups = nodeChangeGroups(payload, query)
  const { modified, added, removed, uncertain: uncertainNodes } = groups
  const relationshipMatches = relationships.filter((relationship) => relationshipSearchText(relationship).includes(query.trim().toLocaleLowerCase()))
  const uncertainRelationshipMatches = uncertainRelationships.filter((relationship) => relationshipSearchText(relationship).includes(query.trim().toLocaleLowerCase()))
  const hasVisible =
    (filterAllows(filter, 'MODIFIED') && modified.length > 0) ||
    (filterAllows(filter, 'ADDED') && added.length > 0) ||
    (filterAllows(filter, 'REMOVED') && removed.length > 0) ||
    (filterAllows(filter, 'RELATIONSHIP') && relationshipMatches.length > 0) ||
    (filterAllows(filter, 'UNCERTAIN', true) && (uncertainNodes.length > 0 || uncertainRelationshipMatches.length > 0 || payload.diff.uncertainties.length > 0))

  if (payload.diff.status === 'NO_BASELINE') return <ChangesState payload={payload} kind="BASELINE" loading={loading} onCompare={onCompare} />
  if (payload.diff.status === 'NO_CHANGES') return <ChangesState payload={payload} kind="NO_CHANGES" loading={loading} onCompare={onCompare} />
  if (!['CHANGED', 'PARTIAL'].includes(payload.diff.status)) return <ChangesState payload={payload} kind="UNAVAILABLE" loading={loading} onCompare={onCompare} />

  return <div className="page changes-page">
    <header className="page-heading changes-heading"><div><p>Changes</p><h1>Changes since {payload.comparison.previous ? formatLocalTime(payload.comparison.previous.capturedAt) : 'the previous snapshot'}</h1><span>Canonical infrastructure observations compared locally. No restore or remote write actions are available.</span></div><ComparisonSelect payload={payload} disabled={loading} onCompare={onCompare} /></header>
    {error && <p className="changes-error" role="status">{error}</p>}
    <div className="changes-layout">
      <div className="changes-main">
        <dl className="change-summary" aria-label="Change summary">
          <SummaryItem symbol="+" label="Added" value={summary.addedNodes} />
          <SummaryItem symbol="~" label="Modified" value={summary.modifiedNodes} />
          <SummaryItem symbol="−" label="Removed" value={summary.removedNodes} />
          <SummaryItem symbol="↗" label="Relationships" value={summary.addedRelationships + summary.removedRelationships} />
          <SummaryItem symbol="?" label="Uncertain" value={summary.uncertainComparisons} />
        </dl>
        <div className="changes-toolbar">
          <div className="change-filters" aria-label="Filter changes">{(['ALL', 'ADDED', 'MODIFIED', 'REMOVED', 'RELATIONSHIPS', 'UNCERTAIN'] as const).map((value) => <button type="button" key={value} className={filter === value ? 'is-active' : ''} aria-pressed={filter === value} onClick={() => setFilter(value)}>{value[0] + value.slice(1).toLowerCase()}</button>)}</div>
          <label className="changes-search"><Search size={14} /><span className="sr-only">Filter changes</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Filter changes…" /></label>
        </div>
        {filterAllows(filter, 'MODIFIED') && <ChangeSection title="Modified" icon={<RefreshCw size={14} />} count={modified.length}>{modified.map((change) => <NodeChangeCard key={change.id} change={change} resource={resources.get(change.nodeId) ?? null} onInspect={onInspect} onNavigate={onNavigate} />)}</ChangeSection>}
        {filterAllows(filter, 'ADDED') && <ChangeSection title="Added" icon={<Plus size={14} />} count={added.length}>{added.map((change) => <NodeChangeCard key={change.id} change={change} resource={resources.get(change.nodeId) ?? null} onInspect={onInspect} onNavigate={onNavigate} />)}</ChangeSection>}
        {filterAllows(filter, 'REMOVED') && <ChangeSection title="Removed" icon={<Minus size={14} />} count={removed.length}>{removed.map((change) => <NodeChangeCard key={change.id} change={change} resource={resources.get(change.nodeId) ?? null} onInspect={onInspect} onNavigate={onNavigate} />)}</ChangeSection>}
        {filterAllows(filter, 'RELATIONSHIP') && <ChangeSection title="Relationship changes" icon={<GitCompareArrows size={14} />} count={relationshipMatches.length}>{relationshipMatches.map((relationship) => <RelationshipCard key={relationship.id} relationship={relationship} onInspect={onInspect} onNavigate={onNavigate} />)}</ChangeSection>}
        {filterAllows(filter, 'UNCERTAIN', true) && <ChangeSection title="Uncertain" icon={<CircleHelp size={14} />} count={uncertainNodes.length + uncertainRelationshipMatches.length + payload.diff.uncertainties.length} subtle>
          {payload.diff.uncertainties.map((uncertainty) => <UncertaintyCard key={`${uncertainty.subsystem}:${uncertainty.reason}`} uncertainty={uncertainty} onInspect={onInspect} />)}
          {uncertainNodes.map((change) => <NodeChangeCard key={change.id} change={change} resource={resources.get(change.nodeId) ?? null} onInspect={onInspect} onNavigate={onNavigate} />)}
          {uncertainRelationshipMatches.map((relationship) => <RelationshipCard key={relationship.id} relationship={relationship} onInspect={onInspect} onNavigate={onNavigate} />)}
        </ChangeSection>}
        {!hasVisible && <p className="empty-state">No changes match this filter.</p>}
      </div>
      <HistoryList payload={payload} loading={loading} onCompare={onCompare} />
    </div>
  </div>
}

export function ChangeInspector({ selection, payload, onClose, onNavigate }: { selection: ChangeSelection | null; payload: ChangesPayload; onClose: () => void; onNavigate: (nodeId: string) => void }) {
  if (!selection) return <aside className="inspector"><header><span>Change details</span><button type="button" onClick={onClose} aria-label="Close change details"><X size={16} /></button></header><p className="inspector-empty">Select a change to inspect its sanitized comparison details.</p></aside>
  if (selection.kind === 'UNCERTAINTY') {
    const copy = uncertaintyCopy(selection.uncertainty.subsystem)
    return <aside className="inspector"><header><span>Change details</span><button type="button" onClick={onClose} aria-label="Close change details"><X size={16} /></button></header><div className="inspector-title"><h2>{copy.title}</h2><p>Discovery uncertainty</p></div><section className="inspector-section"><p className="change-detail-copy">{copy.body}</p></section><CaptureDetails payload={payload} /></aside>
  }
  if (selection.kind === 'RELATIONSHIP') {
    const relationship = selection.relationship
    return <aside className="inspector"><header><span>Change details</span><button type="button" onClick={onClose} aria-label="Close change details"><X size={16} /></button></header><div className="inspector-title"><h2>{relationship.subject?.label ?? 'Relationship change'}</h2><p>{relationLabel(relationship.relation)}</p></div>{relationship.subjectInCurrent && relationship.subject && <div className="inspector-actions"><button type="button" onClick={() => onNavigate(relationship.subject!.id)}>Open current resource</button></div>}<section className="inspector-section"><h3>Before and after</h3><dl><DetailRow label="Previous" value={relationship.beforeTarget?.label ?? 'Not available'} /><DetailRow label="Current" value={relationship.afterTarget?.label ?? 'Not available'} /></dl></section><section className="inspector-section"><h3>Canonical records</h3><dl>{relationship.records.map((record) => <DetailRow key={record.id} label={record.kind === 'RELATIONSHIP_ADDED' ? 'Added' : 'Removed'} value={`${record.edge.source} → ${record.edge.target}`} />)}</dl></section><CaptureDetails payload={payload} /></aside>
  }
  const { change, resource } = selection
  const evidence = evidenceLabel(change.evidence)
  const historical = change.kind === 'REMOVED' ? resource?.before : resource?.after
  return <aside className="inspector"><header><span>Change details</span><button type="button" onClick={onClose} aria-label="Close change details"><X size={16} /></button></header><div className="inspector-title"><h2>{change.label}</h2><p>{resourceTypeLabel(change.nodeType)} · {change.kind.toLocaleLowerCase()}</p>{evidence && <span className="change-evidence">{evidence}</span>}</div>{resource?.after && <div className="inspector-actions"><button type="button" onClick={() => onNavigate(change.nodeId)}>Open current resource</button></div>}{change.fields.length > 0 && <section className="inspector-section"><h3>Field changes</h3><dl>{change.fields.map((field) => <div key={field.field}><dt>{fieldLabel(field.field)}</dt><dd><span>{field.before ?? 'Not available'}</span><ArrowRight size={12} aria-hidden="true" /><span>{field.after ?? 'Not available'}</span></dd></div>)}</dl></section>}{historical && change.fields.length === 0 && <section className="inspector-section"><h3>{change.kind === 'REMOVED' ? 'Previously observed' : 'Observed values'}</h3><dl>{Object.entries(historical.metadata).sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => <DetailRow key={key} label={key} value={value} />)}</dl></section>}<CaptureDetails payload={payload} /></aside>
}

function ChangesState({ payload, kind, loading, onCompare }: { payload: ChangesPayload; kind: 'BASELINE' | 'NO_CHANGES' | 'UNAVAILABLE'; loading: boolean; onCompare: ChangesPageProps['onCompare'] }) {
  const baseline = kind === 'BASELINE'
  const { title, body: copy } = stateCopy(kind === 'BASELINE' ? 'NO_BASELINE' : kind === 'NO_CHANGES' ? 'NO_CHANGES' : 'UNKNOWN')
  return <div className="page changes-page"><header className="page-heading"><div><p>Changes</p><h1>{title}</h1><span>{copy}</span></div><ComparisonSelect payload={payload} disabled={loading} onCompare={onCompare} /></header><section className="changes-state"><span>{baseline ? <Clock3 size={21} /> : kind === 'NO_CHANGES' ? <GitCompareArrows size={21} /> : <CircleHelp size={21} />}</span><div><strong>{title}</strong><p>{copy}</p><dl><DetailRow label="Current" value={formatLocalTime(payload.comparison.current.capturedAt)} />{payload.comparison.previous && <DetailRow label="Previous" value={formatLocalTime(payload.comparison.previous.capturedAt)} />}</dl></div></section>{payload.history.length > 0 && <HistoryList payload={payload} loading={loading} onCompare={onCompare} />}</div>
}

function ComparisonSelect({ payload, disabled, onCompare }: { payload: ChangesPayload; disabled: boolean; onCompare: ChangesPageProps['onCompare'] }) {
  const { latest, older } = comparisonCandidates(payload)
  if (!latest || older.length === 0) return null
  const selected = older.some((entry) => entry.snapshotId === payload.diff.previousSnapshotId) ? payload.diff.previousSnapshotId ?? '' : ''
  return <label className="comparison-select"><span>Compare latest with</span><select value={selected} disabled={disabled} onChange={(event) => event.target.value && onCompare(event.target.value, latest.snapshotId)}><option value="" disabled>Choose snapshot</option>{older.map((entry) => <option key={entry.snapshotId} value={entry.snapshotId}>{formatLocalTime(entry.capturedAt)}</option>)}</select></label>
}

function HistoryList({ payload, loading, onCompare }: { payload: ChangesPayload; loading: boolean; onCompare: ChangesPageProps['onCompare'] }) {
  const latest = payload.history[0]
  return <aside className="changes-history" aria-label="Snapshot history"><header><History size={14} /><h2>History</h2><span>{payload.history.length}</span></header><div>{payload.history.map((entry, index) => <button type="button" key={entry.snapshotId} disabled={loading || index === 0 || !latest} onClick={() => latest && onCompare(entry.snapshotId, latest.snapshotId)} className={entry.snapshotId === payload.diff.previousSnapshotId ? 'is-selected' : ''}><span><strong>{formatLocalTime(entry.capturedAt)}</strong><small title={exactLocalTime(entry.capturedAt)}>{index === 0 ? 'Latest saved snapshot' : `Fingerprint ${entry.fingerprint}`}</small></span>{index > 0 && <GitCompareArrows size={13} />}</button>)}</div></aside>
}

function SummaryItem({ symbol, label, value }: { symbol: string; label: string; value: number }) {
  return <div><dt><span>{symbol}</span>{label}</dt><dd>{value}</dd></div>
}

function ChangeSection({ title, icon, count, subtle, children }: { title: string; icon: React.ReactNode; count: number; subtle?: boolean; children: React.ReactNode }) {
  if (!count) return null
  return <section className={`change-section ${subtle ? 'change-section--subtle' : ''}`}><header><span>{icon}</span><h2>{title}</h2><small>{count}</small></header><div>{children}</div></section>
}

function NodeChangeCard({ change, resource, onInspect, onNavigate }: { change: NodeChange; resource: ChangeResourceSnapshot | null; onInspect: ChangesPageProps['onInspect']; onNavigate: ChangesPageProps['onNavigate'] }) {
  const evidence = evidenceLabel(change.evidence)
  return <article className={`change-card change-card--${change.kind.toLocaleLowerCase()} ${change.evidence === 'UNCONFIRMED_REMOVAL' ? 'change-card--uncertain' : ''}`}><div className="change-card__identity"><button type="button" onClick={() => resource?.after ? onNavigate(change.nodeId) : onInspect({ kind: 'NODE', change, resource })}><strong>{change.label}</strong><small>{resourceTypeLabel(change.nodeType)}</small></button>{evidence && <span className="change-evidence" title={change.evidence === 'NEWLY_OBSERVED' ? 'Visible now, but the previous scan was incomplete, so its first appearance cannot be confirmed.' : undefined}>{evidence}</span>}</div>{change.fields.length > 0 && <dl className="change-fields">{change.fields.map((field) => <div key={field.field}><dt>{fieldLabel(field.field)}</dt><dd><span>{field.before ?? 'Not available'}</span><ArrowRight size={12} /><span>{field.after ?? 'Not available'}</span></dd></div>)}</dl>}<button type="button" className="change-card__details" onClick={() => onInspect({ kind: 'NODE', change, resource })}>Details</button></article>
}

function RelationshipCard({ relationship, onInspect, onNavigate }: { relationship: RelationshipPresentation; onInspect: ChangesPageProps['onInspect']; onNavigate: ChangesPageProps['onNavigate'] }) {
  const changed = relationship.beforeTarget && relationship.afterTarget
  return <article className={`change-card change-card--relationship ${relationship.uncertain ? 'change-card--uncertain' : ''}`}><div className="change-card__identity"><button type="button" onClick={() => relationship.subjectInCurrent && relationship.subject ? onNavigate(relationship.subject.id) : onInspect({ kind: 'RELATIONSHIP', relationship })}><strong>{relationship.subject?.label ?? 'Relationship'}</strong><small>{relationship.subject ? resourceTypeLabel(relationship.subject.type) : 'Relationship'} · {relationLabel(relationship.relation)}</small></button>{relationship.uncertain && <span className="change-evidence">Removal unconfirmed</span>}</div><div className="relationship-change"><span>{relationship.beforeTarget?.label ?? (changed ? 'Not available' : 'Not previously observed')}</span><ArrowRight size={13} /><span>{relationship.afterTarget?.label ?? (changed ? 'Not available' : 'No longer observed')}</span></div><button type="button" className="change-card__details" onClick={() => onInspect({ kind: 'RELATIONSHIP', relationship })}>Details</button></article>
}

function UncertaintyCard({ uncertainty, onInspect }: { uncertainty: DiffUncertainty; onInspect: ChangesPageProps['onInspect'] }) {
  const copy = uncertaintyCopy(uncertainty.subsystem)
  return <button type="button" className="uncertainty-card" onClick={() => onInspect({ kind: 'UNCERTAINTY', uncertainty })}><CircleHelp size={15} /><span><strong>{copy.title}</strong><small>{copy.body}</small></span></button>
}

function CaptureDetails({ payload }: { payload: ChangesPayload }) {
  return <section className="inspector-section"><h3>Comparison</h3><dl>{payload.comparison.previous && <DetailRow label="Previous" value={exactLocalTime(payload.comparison.previous.capturedAt)} />}<DetailRow label="Current" value={exactLocalTime(payload.comparison.current.capturedAt)} /></dl></section>
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>
}

function relationshipSearchText(relationship: RelationshipPresentation): string {
  return [relationship.subject?.label, resourceTypeLabel(relationship.subject?.type ?? ''), relationLabel(relationship.relation), relationship.beforeTarget?.label, relationship.afterTarget?.label].filter(Boolean).join(' ').toLocaleLowerCase()
}
