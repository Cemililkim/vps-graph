import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { HELPER_INSTALL_COMMANDS, HELPER_PATH, caddyEmptyCopy, canDisconnect, connectionIssue, disconnectedFormState, discoveryStatusCopy, dockerEmptyCopy, helperAvailability, nextTheme, panelDefaults, resolveTheme, scanSessionState, validateConnection } from '../src/ui-state.ts'

test('theme choice prefers a valid saved mode and otherwise follows the host preference', () => {
  assert.equal(resolveTheme('light', false), 'light')
  assert.equal(resolveTheme('dark', true), 'dark')
  assert.equal(resolveTheme(null, true), 'light')
  assert.equal(resolveTheme('unsupported', false), 'dark')
  assert.equal(nextTheme('dark'), 'light')
})

test('first presentation keeps Explorer visible at practical widths and Details closed', () => {
  assert.deepEqual(panelDefaults(820), { explorerOpen: false, inspectorOpen: false })
  assert.deepEqual(panelDefaults(821), { explorerOpen: true, inspectorOpen: false })
})

test('disconnect keeps remembered fields or clears only the unremembered form', () => {
  const remembered = { host: 'vps.example', port: '22', username: 'vpsgraph', privateKeyPath: 'C:\\keys\\id_ed25519', rememberConnection: true }
  assert.deepEqual(disconnectedFormState(remembered), remembered)
  assert.deepEqual(disconnectedFormState({ ...remembered, rememberConnection: false }), { host: '', port: '22', username: '', privateKeyPath: '', rememberConnection: false })
})

test('disconnect cannot race an active scan into republishing stale state', () => {
  assert.equal(canDisconnect('CONNECTING'), false)
  assert.equal(canDisconnect('SCANNING'), false)
  assert.equal(canDisconnect('CONNECTED'), true)
  assert.equal(canDisconnect('ERROR'), true)
  assert.equal(canDisconnect('IDLE'), true)
})

test('a failed rescan retains the last successful graph while a first failure does not', () => {
  assert.deepEqual(scanSessionState(false, 'ERROR'), { hasSuccessfulGraph: false, retainedAfterFailure: false })
  assert.deepEqual(scanSessionState(false, 'CONNECTED'), { hasSuccessfulGraph: true, retainedAfterFailure: false })
  assert.deepEqual(scanSessionState(true, 'CONNECTING'), { hasSuccessfulGraph: true, retainedAfterFailure: false })
  assert.deepEqual(scanSessionState(true, 'ERROR'), { hasSuccessfulGraph: true, retainedAfterFailure: true })
})

test('discovery statuses use stable product copy and fail safely for unknown values', () => {
  assert.deepEqual(discoveryStatusCopy('Systemd service', 'DISCOVERED'), { label: 'Available' })
  assert.equal(discoveryStatusCopy('Systemd service', 'NOT_SYSTEMD').label, 'Not supported')
  assert.match(discoveryStatusCopy('host listener', 'PARTIAL').message ?? '', /may be missing/)
  assert.equal(discoveryStatusCopy('host listener', 'FUTURE_STATUS').label, 'Unknown')
})

test('connection onboarding validates fields before bridge submission', () => {
  const valid = { host: '2001:db8::10', port: '22', username: 'vpsgraph', privateKeyPath: 'C:\\keys\\id_ed25519' }
  assert.equal(validateConnection(valid), null)
  assert.equal(validateConnection({ ...valid, host: ' ' })?.field, 'host')
  assert.equal(validateConnection({ ...valid, port: '0' })?.field, 'port')
  assert.equal(validateConnection({ ...valid, port: '70000' })?.field, 'port')
  assert.equal(validateConnection({ ...valid, port: '22.5' })?.field, 'port')
  assert.equal(validateConnection({ ...valid, username: '' })?.field, 'username')
  assert.equal(validateConnection({ ...valid, privateKeyPath: '' })?.field, 'privateKeyPath')
})

test('fixed scan failures produce actionable product-owned guidance without raw input', () => {
  const expected = {
    SSH_HOST_UNREACHABLE: 'Host unreachable',
    SSH_CONNECTION_REFUSED: 'Connection refused',
    SSH_TIMEOUT: 'Connection timed out',
    SSH_AUTH_FAILED: 'Authentication failed',
    SSH_KEY_NOT_FOUND: 'Private key not found',
    SSH_KEY_INVALID: 'Private key is invalid',
    SSH_KEY_UNSUPPORTED: 'Private key is unsupported',
    SSH_HOST_KEY_UNKNOWN: 'Server is not trusted yet',
    SSH_HOST_KEY_MISMATCH: 'Server identity changed',
  }
  Object.entries(expected).forEach(([code, title]) => {
    const guidance = connectionIssue(code)
    assert.equal(guidance.title, title)
    assert.ok(guidance.action.length > 10)
    assert.doesNotMatch(JSON.stringify(guidance), /private-key contents|raw command|stack trace/i)
  })
  assert.match(connectionIssue('SSH_KEY_UNSUPPORTED').explanation, /passphrase/i)
  assert.equal(connectionIssue('SSH_HOST_KEY_UNKNOWN').guide, 'KNOWN_HOSTS')
  assert.equal(connectionIssue('SSH_HOST_KEY_MISMATCH').guide, 'KNOWN_HOSTS')
})

test('helper provenance distinguishes absent unauthorized stale current and software-absent states', () => {
  assert.equal(helperAvailability('HELPER_NOT_INSTALLED', 'UNKNOWN', 'UNKNOWN'), 'NOT_DETECTED')
  assert.equal(helperAvailability('HELPER_NOT_AUTHORIZED', 'UNKNOWN', 'UNKNOWN'), 'NOT_AUTHORIZED')
  assert.equal(helperAvailability('SCAN_FAILED', 'UNKNOWN', 'UNKNOWN'), 'DISCOVERY_UNAVAILABLE')
  assert.equal(helperAvailability('AVAILABLE', 'UNKNOWN', 'UNKNOWN'), 'UPDATE_RECOMMENDED')
  assert.equal(helperAvailability('AVAILABLE', 'DISCOVERED', 'DISCOVERED'), 'READY')
  assert.equal(dockerEmptyCopy('DOCKER_UNAVAILABLE'), 'No Docker applications detected.')
  assert.match(dockerEmptyCopy('HELPER_NOT_INSTALLED'), /discovery unavailable/i)
  assert.equal(caddyEmptyCopy('AVAILABLE', 'NOT_DETECTED'), 'No supported Caddy routes detected.')
  assert.match(caddyEmptyCopy('HELPER_NOT_AUTHORIZED', 'NOT_DETECTED'), /discovery unavailable/i)
})

test('helper guide commands remain static argument-free project-owned templates', () => {
  assert.equal(HELPER_PATH, '/usr/local/libexec/vpsgraph-docker-scan')
  assert.deepEqual(HELPER_INSTALL_COMMANDS, [
    'cd server-helper',
    'sudo sh ./install.sh <scan-user>',
    'sudo -l -U <scan-user>',
    'sudo -u <scan-user> sudo -n /usr/local/libexec/vpsgraph-docker-scan',
  ])
  assert.ok(HELPER_INSTALL_COMMANDS.every((command) => !command.includes('${') && !command.includes('docker ps')))
})

test('getting-started helper commands match the installed helper path', () => {
  const guide = readFileSync(new URL('../../docs/GETTING_STARTED.md', import.meta.url), 'utf8')
  const installer = readFileSync(new URL('../../server-helper/install.sh', import.meta.url), 'utf8')
  HELPER_INSTALL_COMMANDS.forEach((command) => assert.ok(guide.includes(command)))
  assert.ok(installer.includes(`HELPER_TARGET=${HELPER_PATH}`))
})
