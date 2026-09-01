import test from 'node:test'
import assert from 'node:assert/strict'
import { comparisonCandidates, confirmedChangeCount, evidenceLabel, filterAllows, formatLocalTime, nodeChangeGroups, normalizeChangesResponse, overviewChangeSummary, parseChangesResponse, relationLabel, relationshipPresentations, resourceTypeLabel, stateCopy, uncertainRelationshipPresentations, uncertaintyCopy } from '../src/changes-model.ts'
import { baselinePayload, changedPayload, noChangesPayload } from './fixtures/changes.ts'

test('baseline and no-change states use distinct calm product copy', () => {
  assert.equal(stateCopy(baselinePayload.diff.status).title, 'Baseline captured')
  assert.match(stateCopy(noChangesPayload.diff.status).title, /No infrastructure changes/)
  assert.equal(overviewChangeSummary(baselinePayload).title, 'Baseline captured')
  assert.equal(overviewChangeSummary(noChangesPayload).title, 'No changes since previous scan')
})

test('summary uses authoritative M6B counts without promoting uncertain removals', () => {
  assert.equal(confirmedChangeCount(changedPayload), 8)
  assert.equal(changedPayload.diff.summary.removedNodes, 1)
  assert.equal(changedPayload.diff.summary.uncertainComparisons, 3)
  assert.match(overviewChangeSummary(changedPayload).detail, /\+2 added · ~1 modified · −1 removed/)
})

test('node projection separates added modified removed and unconfirmed removal evidence', () => {
  const groups = nodeChangeGroups(changedPayload)
  assert.deepEqual(groups.added.map((change) => change.label), ['worker', 'worker.service'])
  assert.deepEqual(groups.modified.map((change) => change.label), ['n8n'])
  assert.deepEqual(groups.removed.map((change) => change.label), ['old-worker'])
  assert.deepEqual(groups.uncertain.map((change) => change.label), ['systemd-fsck@dev-disk-by\\x2duuid-352C\\x2dFCAB.service', '[fe80::eac9:da0a:d32:2190%eth0]:19999/tcp'])
  assert.equal(evidenceLabel(groups.added[1].evidence), 'Newly observed')
  assert.equal(evidenceLabel(groups.uncertain[0].evidence), 'Removal unconfirmed')
})

test('field filtering preserves safe long values escaped units and scoped IPv6 literally', () => {
  assert.equal(nodeChangeGroups(changedPayload, 'n8nio/n8n:1.3-with-a-very-long').modified.length, 1)
  assert.equal(nodeChangeGroups(changedPayload, '\\x2duuid').uncertain.length, 1)
  assert.equal(nodeChangeGroups(changedPayload, '%eth0').uncertain.length, 1)
  assert.equal(nodeChangeGroups(changedPayload, 'not-present').added.length, 0)
})

test('canonical relationship pairs group deterministically and retain both records', () => {
  const groups = relationshipPresentations(changedPayload)
  assert.equal(groups.length, 2)
  const api = groups.find((group) => group.subject?.label === 'api.example.com')
  assert.equal(api?.beforeTarget?.label, 'portfolio-api:4000')
  assert.equal(api?.afterTarget?.label, 'api-v2:4000')
  assert.equal(api?.records.length, 2)
  assert.equal(api?.subjectInCurrent, true)
  assert.equal(uncertainRelationshipPresentations(changedPayload).length, 0)
})

test('history exposes lightweight latest and older comparison choices', () => {
  const { latest, older } = comparisonCandidates(changedPayload)
  assert.equal(latest?.snapshotId, changedPayload.comparison.current.snapshotId)
  assert.equal(older.length, 2)
  assert.ok(changedPayload.history.every((entry) => entry.fingerprint.length === 10))

  const baseline = comparisonCandidates(baselinePayload)
  assert.equal(baseline.latest?.snapshotId, baselinePayload.comparison.current.snapshotId)
  assert.deepEqual(baseline.older, [])
})

test('filters resource navigation context local time and unknown enums remain safe', () => {
  assert.equal(filterAllows('REMOVED', 'REMOVED'), true)
  assert.equal(filterAllows('REMOVED', 'REMOVED', true), false)
  assert.equal(filterAllows('UNCERTAIN', 'REMOVED', true), true)
  assert.equal(formatLocalTime('2026-08-30T11:42:00Z', 'en-US', 'UTC'), 'Aug 30, 2026, 11:42 AM')
  assert.equal(resourceTypeLabel('FUTURE_SAFE_TYPE'), 'Future safe type')
  assert.equal(relationLabel('FUTURE_RELATION'), 'Future relation')
  assert.equal(stateCopy('FUTURE_STATUS').title, 'This comparison is unavailable.')
  assert.match(uncertaintyCopy('FUTURE_SUBSYSTEM').body, /cannot be verified/)
})

test('bridge parsing accepts typed local payloads and fails closed on malformed data', () => {
  assert.equal(parseChangesResponse(JSON.stringify({ ok: true, payload: changedPayload })).payload?.diff.status, 'PARTIAL')
  assert.equal(parseChangesResponse('{broken').error?.code, 'INVALID_CHANGES_PAYLOAD')
  assert.equal(parseChangesResponse(JSON.stringify({ ok: false, error: { code: 'SNAPSHOT_UNAVAILABLE', message: 'Unavailable.' } })).error?.message, 'Unavailable.')
})

test('Changes payload schema version is required at the Kotlin bridge boundary', () => {
  const omittedSchemaVersion = structuredClone(changedPayload)
  delete (omittedSchemaVersion as Partial<typeof omittedSchemaVersion>).schemaVersion
  assert.equal(normalizeChangesResponse({ ok: true, payload: omittedSchemaVersion }).error?.code, 'INVALID_CHANGES_PAYLOAD')
  assert.equal(normalizeChangesResponse({ ok: true, payload: changedPayload }).payload?.schemaVersion, 1)
})

test('legacy Kotlin default omission normalizes no-change and baseline collections at the bridge boundary', () => {
  for (const payload of [noChangesPayload, baselinePayload]) {
    const legacy = structuredClone(payload)
    delete (legacy.diff as Partial<typeof legacy.diff>).nodeChanges
    delete (legacy.diff as Partial<typeof legacy.diff>).relationshipChanges
    delete (legacy.diff as Partial<typeof legacy.diff>).uncertainties
    for (const field of ['addedNodes', 'removedNodes', 'modifiedNodes', 'addedRelationships', 'removedRelationships', 'uncertainComparisons', 'hasChanges', 'isComplete'] as const) delete (legacy.diff.summary as Partial<typeof legacy.diff.summary>)[field]
    const parsed = parseChangesResponse(JSON.stringify({ ok: true, payload: legacy }))
    assert.equal(parsed.ok, true)
    assert.deepEqual(parsed.payload?.diff.nodeChanges, [])
    assert.deepEqual(parsed.payload?.diff.relationshipChanges, [])
    assert.deepEqual(parsed.payload?.diff.uncertainties, [])
    assert.equal(confirmedChangeCount(parsed.payload ?? null), 0)
  }
})

test('unknown values and invalid optional timestamps remain display-safe while malformed required records fail closed', () => {
  const payload = structuredClone(changedPayload)
  payload.diff.nodeChanges[0].kind = 'FUTURE_CHANGE'
  payload.diff.nodeChanges[0].evidence = 'FUTURE_EVIDENCE'
  payload.comparison.current.capturedAt = 'not-a-timestamp'
  const parsed = normalizeChangesResponse({ ok: true, payload })
  assert.equal(parsed.ok, true)
  assert.equal(parsed.payload?.diff.nodeChanges[0].kind, 'FUTURE_CHANGE')
  assert.equal(formatLocalTime(parsed.payload?.comparison.current.capturedAt ?? ''), 'Unknown time')
  assert.equal(normalizeChangesResponse({ ok: true, payload: { schemaVersion: 1 } }).error?.code, 'INVALID_CHANGES_PAYLOAD')
})
