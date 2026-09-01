import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyConnectionPreferences, parseConnectionPreferences } from '../src/connection-preferences.ts'

test('remembered connection restores only explicit non-secret form fields without auto-connect state', () => {
  const restored = parseConnectionPreferences(JSON.stringify({
    remembered: true,
    host: 'vps.example',
    port: 22,
    username: 'vpsgraph',
    privateKeyPath: '/keys/id_ed25519',
    privateKeyExists: true,
    password: 'must-not-enter-state',
    privateKeyContents: 'must-not-enter-state',
  }))

  assert.deepEqual(restored, {
    remembered: true,
    host: 'vps.example',
    port: '22',
    username: 'vpsgraph',
    privateKeyPath: '/keys/id_ed25519',
    privateKeyMissing: false,
  })
  assert.equal('autoConnect' in restored, false)
  assert.equal('password' in restored, false)
  assert.equal('privateKeyContents' in restored, false)
})

test('unchecked malformed and missing-key preferences are safe and never guess a fallback', () => {
  assert.deepEqual(parseConnectionPreferences('{"remembered":false}'), emptyConnectionPreferences)
  assert.deepEqual(parseConnectionPreferences('{broken'), emptyConnectionPreferences)
  const missing = parseConnectionPreferences(JSON.stringify({ remembered: true, host: 'vps', port: 22, username: 'user', privateKeyPath: '/missing/key', privateKeyExists: false }))
  assert.equal(missing.privateKeyPath, '/missing/key')
  assert.equal(missing.privateKeyMissing, true)
})
