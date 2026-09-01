import assert from 'node:assert/strict'
import test from 'node:test'
import { deriveVisibleGraph, layoutVisibleGraph, nodePresentation, preferredView, searchGraph } from '../src/graph-utils.ts'
import { busyVpsGraph } from './fixtures/busy-vps.ts'

test('view modes use progressive disclosure without mutating the canonical graph', () => {
  const before = JSON.stringify(busyVpsGraph)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'OVERVIEW').nodes.some((node) => ['PORT', 'NETWORK', 'MOUNT'].includes(node.type)), false)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'NETWORKING').nodes.some((node) => node.type === 'PORT'), true)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'NETWORKING').nodes.some((node) => node.type === 'NETWORK'), true)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'NETWORKING').nodes.some((node) => node.type === 'MOUNT'), false)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'STORAGE').nodes.some((node) => node.type === 'MOUNT'), true)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'STORAGE').nodes.some((node) => node.type === 'PORT'), false)
  assert.equal(deriveVisibleGraph(busyVpsGraph, 'EVERYTHING').nodes.length, busyVpsGraph.nodes.length)
  assert.equal(JSON.stringify(busyVpsGraph), before)
})

test('collapse and focus reduce visible topology deterministically', () => {
  const collapsed = deriveVisibleGraph(busyVpsGraph, 'OVERVIEW', { collapsedComposeProjectIds: new Set(['compose:1']) })
  assert.equal(collapsed.nodes.some((node) => node.id.startsWith('compose:1:container')), false)
  const focused = deriveVisibleGraph(busyVpsGraph, 'OVERVIEW', { focusedNodeId: 'compose:2' })
  assert.equal(focused.nodes.every((node) => node.id === 'compose:2' || node.id === 'docker:busy' || node.id.startsWith('compose:2:container')), true)
})

test('search ranks primary exact, prefix, and substring metadata matches', () => {
  assert.equal(searchGraph(busyVpsGraph, 'project-2-service-1')[0].node.label, 'project-2-service-1')
  assert.equal(searchGraph(busyVpsGraph, 'project-2-ser')[0].node.label, 'project-2-service-1')
  assert.equal(searchGraph(busyVpsGraph, 'image-3')[0].node.type, 'DOCKER_CONTAINER')
  assert.equal(preferredView(searchGraph(busyVpsGraph, 'web')[0].node), 'NETWORKING')
})

test('layout is stable and mount/port presentation remains compact', () => {
  const visible = deriveVisibleGraph(busyVpsGraph, 'EVERYTHING')
  const layout = layoutVisibleGraph(visible, 'EVERYTHING')
  assert.deepEqual(layout, layoutVisibleGraph(visible, 'EVERYTHING'))
  assert.ok(layout.get('compose:1:container:4')!.y < layout.get('compose:2:container:1')!.y)
  const mount = busyVpsGraph.nodes.find((node) => node.type === 'MOUNT')!
  assert.equal(nodePresentation(mount).title, 'data-1')
  const port = busyVpsGraph.nodes.find((node) => node.type === 'PORT')!
  assert.equal(nodePresentation(port).subtitle, 'INTERNAL')
})
