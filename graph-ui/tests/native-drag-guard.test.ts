import assert from 'node:assert/strict'
import test from 'node:test'
import { preventNativeDrag } from '../src/native-drag-guard.ts'

test('native drag guard cancels only the drag default action', () => {
  let prevented = false
  const selection = 'running'
  const target = { closest: () => null }
  const event = { target, preventDefault: () => { prevented = true } }
  assert.equal(preventNativeDrag(event as never), true)
  assert.equal(prevented, true)
  assert.equal(selection, 'running')
})

test('native drag guard permits explicit product drag opt-in', () => {
  let prevented = false
  const event = {
    target: { closest: (selector: string) => selector === '[data-native-drag="allowed"]' ? {} : null },
    preventDefault: () => { prevented = true },
  }

  assert.equal(preventNativeDrag(event as never), false)
  assert.equal(prevented, false)
})
