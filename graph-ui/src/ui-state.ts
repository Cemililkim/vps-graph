export type ThemeMode = 'dark' | 'light'
export type ScanLifecycleState = 'CONNECTING' | 'SCANNING' | 'CONNECTED' | 'ERROR'
export type ConnectionField = 'host' | 'port' | 'username' | 'privateKeyPath'
export type SetupTopic = 'SSH' | 'KNOWN_HOSTS' | 'HELPER'

export interface ConnectionIssue {
  code: string
  title: string
  explanation: string
  action: string
  field?: ConnectionField
  guide?: SetupTopic
}

export interface ConnectionInput {
  host: string
  port: string
  username: string
  privateKeyPath: string
}

export interface ConnectionFormState extends ConnectionInput {
  rememberConnection: boolean
}

export type HelperAvailability = 'READY' | 'UPDATE_RECOMMENDED' | 'NOT_DETECTED' | 'NOT_AUTHORIZED' | 'DISCOVERY_UNAVAILABLE'

export const HELPER_PATH = '/usr/local/libexec/vpsgraph-docker-scan'
export const HELPER_INSTALL_COMMANDS = [
  'cd server-helper',
  'sudo sh ./install.sh <scan-user>',
  'sudo -l -U <scan-user>',
  `sudo -u <scan-user> sudo -n ${HELPER_PATH}`,
] as const

export function validateConnection(input: ConnectionInput): ConnectionIssue | null {
  if (!input.host.trim()) return issue('INVALID_HOST')
  const port = Number(input.port)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return issue('INVALID_PORT')
  if (!input.username.trim()) return issue('INVALID_USERNAME')
  if (!input.privateKeyPath.trim()) return issue('INVALID_PRIVATE_KEY_PATH')
  return null
}

export function disconnectedFormState(current: ConnectionFormState): ConnectionFormState {
  if (current.rememberConnection) return current
  return { host: '', port: '22', username: '', privateKeyPath: '', rememberConnection: false }
}

export function canDisconnect(state: 'IDLE' | ScanLifecycleState): boolean {
  return state !== 'CONNECTING' && state !== 'SCANNING'
}

export function connectionIssue(code?: string): ConnectionIssue {
  return issue(code ?? 'UNKNOWN')
}

export function helperAvailability(dockerState?: string, systemdState?: string, listenerState?: string): HelperAvailability {
  if (dockerState === 'HELPER_NOT_INSTALLED') return 'NOT_DETECTED'
  if (dockerState === 'HELPER_NOT_AUTHORIZED') return 'NOT_AUTHORIZED'
  if (dockerState === 'SCAN_FAILED' || !dockerState) return 'DISCOVERY_UNAVAILABLE'
  if (dockerState === 'AVAILABLE' && systemdState === 'UNKNOWN' && listenerState === 'UNKNOWN') return 'UPDATE_RECOMMENDED'
  return 'READY'
}

export function dockerEmptyCopy(dockerState?: string, detectedCopy = 'No Docker applications detected.'): string {
  if (dockerState === 'AVAILABLE' || dockerState === 'DOCKER_UNAVAILABLE') return detectedCopy
  return 'Docker discovery unavailable. Basic host information remains available; review the optional server helper setup.'
}

export function caddyEmptyCopy(dockerState?: string, caddyState?: string): string {
  if (dockerState !== 'AVAILABLE') return 'Caddy discovery unavailable. Basic host information remains available; review the optional server helper setup.'
  if (caddyState === 'NOT_DETECTED') return 'No supported Caddy routes detected.'
  return 'No validated Caddy routes are available from this scan.'
}

function issue(code: string): ConnectionIssue {
  switch (code) {
    case 'INVALID_HOST': return { code, title: 'Host is required', explanation: 'Enter a hostname, IPv4 address, or IPv6 address.', action: 'Check the server address and try again.', field: 'host' }
    case 'INVALID_PORT': return { code, title: 'SSH port is invalid', explanation: 'The SSH port must be a whole number from 1 to 65535.', action: 'Use port 22 unless this server uses a different SSH port.', field: 'port' }
    case 'INVALID_USERNAME': return { code, title: 'Username is required', explanation: 'Enter the Linux account VPS Graph should use for read-only discovery.', action: 'A dedicated low-privilege account is recommended; root is not required.', field: 'username' }
    case 'INVALID_PRIVATE_KEY_PATH': return { code, title: 'Private key is required', explanation: 'Choose an existing SSH private-key file on this computer.', action: 'Use Browse to select the key used by this Linux account.', field: 'privateKeyPath', guide: 'SSH' }
    case 'SSH_HOST_UNREACHABLE': return { code, title: 'Host unreachable', explanation: 'VPS Graph could not resolve or reach the SSH host.', action: 'Check the host, network connection, and SSH port.', field: 'host' }
    case 'SSH_CONNECTION_REFUSED': return { code, title: 'Connection refused', explanation: 'The host responded, but nothing accepted the SSH connection on this port.', action: 'Verify the SSH port and SSH service.', field: 'port' }
    case 'SSH_TIMEOUT': return { code, title: 'Connection timed out', explanation: 'The SSH connection did not complete before the safety timeout.', action: 'Check the host, SSH port, server status, and whether SSH is reachable from this computer.' }
    case 'SSH_AUTH_FAILED': return { code, title: 'Authentication failed', explanation: 'The server rejected this SSH key for the selected Linux account.', action: 'Check the username and confirm the matching public key is installed for that account.', field: 'username', guide: 'SSH' }
    case 'SSH_KEY_NOT_FOUND': return { code, title: 'Private key not found', explanation: 'The selected private-key file no longer exists.', action: 'Choose the correct local private-key file. VPS Graph will not search for a replacement.', field: 'privateKeyPath', guide: 'SSH' }
    case 'SSH_KEY_UNREADABLE': return { code, title: 'Private key is unreadable', explanation: 'The IDE process cannot read the selected private-key file.', action: 'Choose a readable key file without changing the server.', field: 'privateKeyPath', guide: 'SSH' }
    case 'SSH_KEY_INVALID': return { code, title: 'Private key is invalid', explanation: 'VPS Graph could not use this path as a regular SSH private-key file.', action: 'Choose a supported private-key file.', field: 'privateKeyPath', guide: 'SSH' }
    case 'SSH_KEY_UNSUPPORTED': return { code, title: 'Private key is unsupported', explanation: 'This key format is unsupported or requires a passphrase. Passphrase-protected key UX is not available yet.', action: 'Choose another supported key, such as a dedicated key for VPS Graph. Do not remove encryption from your primary key.', field: 'privateKeyPath', guide: 'SSH' }
    case 'SSH_HOST_KEY_UNKNOWN': return { code, title: 'Server is not trusted yet', explanation: 'This server is not present in your local OpenSSH known_hosts file.', action: 'Connect once with your normal SSH client and verify the fingerprint through a trusted provider or channel.', field: 'host', guide: 'KNOWN_HOSTS' }
    case 'SSH_HOST_KEY_MISMATCH': return { code, title: 'Server identity changed', explanation: 'The saved SSH host key no longer matches this server. A rebuild can cause this, but so can an unexpected server.', action: 'Stop and independently verify the new fingerprint before changing known_hosts.', field: 'host', guide: 'KNOWN_HOSTS' }
    case 'INVALID_SSH_TARGET': return { code, title: 'Connection details are invalid', explanation: 'One or more required SSH fields could not be accepted.', action: 'Review the host, port, username, and private-key path.' }
    case 'PRIVATE_KEY_PICKER_FAILED': return { code, title: 'Private key could not be selected', explanation: 'The native file chooser did not return a usable path.', action: 'Try Browse again or enter the path manually.', field: 'privateKeyPath' }
    case 'SSH_CONNECTION_FAILED': return { code, title: 'SSH connection failed', explanation: 'VPS Graph could not establish the secure SSH transport.', action: 'Check the host, port, network access, and SSH service.' }
    case 'REMOTE_COMMAND_FAILED': return { code, title: 'Host discovery failed', explanation: 'The secure connection completed, but basic read-only host discovery did not.', action: 'Retry the scan. If it repeats, verify that standard hostname and uname commands are available.' }
    default: return { code, title: 'Host scan could not start', explanation: 'VPS Graph could not complete this connection attempt.', action: 'Review the connection details and try again.' }
  }
}

export function scanSessionState(hadSuccessfulGraph: boolean, state: ScanLifecycleState): { hasSuccessfulGraph: boolean; retainedAfterFailure: boolean } {
  const hasSuccessfulGraph = hadSuccessfulGraph || state === 'CONNECTED'
  return { hasSuccessfulGraph, retainedAfterFailure: hadSuccessfulGraph && state === 'ERROR' }
}

export function discoveryStatusCopy(subject: 'Systemd service' | 'host listener', status?: string): { label: string; message?: string } {
  switch (status) {
    case 'DISCOVERED': return { label: 'Available' }
    case 'PARTIAL': return { label: 'Partial', message: `Some ${subject} records may be missing. Other discovered infrastructure remains available.` }
    case 'NOT_SYSTEMD': return { label: 'Not supported', message: 'This host does not use Systemd. Docker and routing data remain available.' }
    case 'NOT_AVAILABLE': return { label: 'Unavailable', message: `${subject} discovery requires the optional VPS Graph server helper.` }
    case 'COMMAND_FAILED': return { label: 'Unavailable', message: `${subject} discovery could not be completed. Other discovered infrastructure remains available.` }
    case 'MALFORMED_OUTPUT': return { label: 'Incomplete', message: `${subject} data was not understood safely, so only validated records are shown.` }
    default: return { label: 'Unknown', message: `${subject} discovery status is unavailable. Other discovered infrastructure remains available.` }
  }
}

export function resolveTheme(stored: string | null, prefersLight: boolean): ThemeMode {
  return stored === 'dark' || stored === 'light' ? stored : prefersLight ? 'light' : 'dark'
}

export function nextTheme(theme: ThemeMode): ThemeMode {
  return theme === 'dark' ? 'light' : 'dark'
}

export function panelDefaults(width: number): { explorerOpen: boolean; inspectorOpen: boolean } {
  return { explorerOpen: width > 820, inspectorOpen: false }
}
