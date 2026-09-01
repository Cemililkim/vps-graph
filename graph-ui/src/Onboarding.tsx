import { useEffect, useState } from 'react'
import { ChevronRight, Clipboard, KeyRound, ServerCog, ShieldCheck, X } from 'lucide-react'
import { HELPER_INSTALL_COMMANDS, HELPER_PATH, helperAvailability, type ConnectionIssue, type SetupTopic } from './ui-state'

export function ConnectionIssueCard({ issue, onOpenGuide }: { issue: ConnectionIssue; onOpenGuide: (topic: SetupTopic) => void }) {
  return <section className="connection-issue" id="connection-issue" role="alert" aria-labelledby="connection-issue-title">
    <div><strong id="connection-issue-title">{issue.title}</strong><p>{issue.explanation}</p><span>{issue.action}</span></div>
    {issue.guide && <button type="button" className="quiet-button" onClick={() => onOpenGuide(issue.guide!)}>Learn more <ChevronRight size={14} /></button>}
  </section>
}

export function ConnectionOrientation({ firstRun, onOpenGuide }: { firstRun: boolean; onOpenGuide: (topic: SetupTopic) => void }) {
  if (!firstRun) return <nav className="connection-help-links" aria-label="Connection help">
    <button type="button" onClick={() => onOpenGuide('SSH')}>SSH setup help</button>
    <button type="button" onClick={() => onOpenGuide('KNOWN_HOSTS')}>Why known_hosts?</button>
    <button type="button" onClick={() => onOpenGuide('HELPER')}>Server helper</button>
  </nav>
  return <section className="connection-prerequisites" aria-labelledby="first-scan-heading">
    <h2 id="first-scan-heading">Before your first scan</h2>
    <ul>
      <li><KeyRound size={15} /><span><strong>Public-key SSH</strong><small>Use an existing key that can sign in to the selected Linux account.</small></span><button type="button" onClick={() => onOpenGuide('SSH')}>Setup help</button></li>
      <li><ShieldCheck size={15} /><span><strong>Verified server identity</strong><small>The server must already be trusted in your local OpenSSH known_hosts file.</small></span><button type="button" onClick={() => onOpenGuide('KNOWN_HOSTS')}>Why?</button></li>
      <li><ServerCog size={15} /><span><strong>Server helper</strong><small>Optional for connecting; enables Docker, Caddy, Systemd, and listener discovery.</small></span><button type="button" onClick={() => onOpenGuide('HELPER')}>Install guide</button></li>
    </ul>
  </section>
}

export function HelperStatus({ dockerState, caddyState, systemdState, listenerState, onOpenGuide }: { dockerState?: string; caddyState?: string; systemdState?: string; listenerState?: string; onOpenGuide: (topic: SetupTopic) => void }) {
  const availability = helperAvailability(dockerState, systemdState, listenerState)
  const label = availability === 'READY' ? 'Ready' : availability === 'UPDATE_RECOMMENDED' ? 'Update recommended' : availability === 'NOT_DETECTED' ? 'Not detected' : availability === 'NOT_AUTHORIZED' ? 'Permission not configured' : 'Discovery unavailable'
  const docker = dockerState === 'AVAILABLE' ? 'Available' : dockerState === 'DOCKER_UNAVAILABLE' ? 'Not detected' : 'Unavailable'
  const caddy = dockerState !== 'AVAILABLE' ? 'Unavailable' : caddyState === 'DISCOVERED' ? 'Available' : caddyState === 'NOT_DETECTED' ? 'Not detected' : 'Incomplete'
  const optional = (state?: string) => state === 'DISCOVERED' ? 'Available' : state === 'NOT_SYSTEMD' ? 'Not supported' : state === 'PARTIAL' ? 'Partial' : 'Unavailable'
  return <section className="helper-status" aria-labelledby="helper-status-title">
    <header><div><p>Deeper discovery</p><h2 id="helper-status-title">Server helper <span>{label}</span></h2></div>{availability !== 'READY' && <button type="button" className="quiet-button" onClick={() => onOpenGuide('HELPER')}>View installation guide</button>}</header>
    <p>{availability === 'READY' ? 'The argument-free read-only helper was used during this scan.' : availability === 'UPDATE_RECOMMENDED' ? 'The helper returned Docker data but does not expose the current optional service and listener sections.' : 'Basic host information remains available without deeper discovery.'}</p>
    <dl><div><dt>Docker / Compose</dt><dd>{docker}</dd></div><div><dt>Caddy routing</dt><dd>{caddy}</dd></div><div><dt>Systemd services</dt><dd>{optional(systemdState)}</dd></div><div><dt>Host listeners</dt><dd>{optional(listenerState)}</dd></div></dl>
  </section>
}

export function SetupGuide({ topic, onClose }: { topic: SetupTopic; onClose: () => void }) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  const title = topic === 'SSH' ? 'SSH setup help' : topic === 'KNOWN_HOSTS' ? 'Verify the server identity' : 'Install the server helper'
  return <div className="modal-backdrop setup-guide-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <section className="setup-guide" role="dialog" aria-modal="true" aria-labelledby="setup-guide-title">
      <header><div><p>VPS Graph setup</p><h2 id="setup-guide-title">{title}</h2></div><button type="button" className="icon-button" onClick={onClose} aria-label="Close setup guide" autoFocus><X size={16} /></button></header>
      {topic === 'SSH' && <SshGuide />}
      {topic === 'KNOWN_HOSTS' && <KnownHostsGuide />}
      {topic === 'HELPER' && <HelperGuide />}
    </section>
  </div>
}

function SshGuide() {
  return <div className="setup-guide__content">
    <p>VPS Graph uses your existing SSH public-key setup. The selected private key is read locally for authentication and stays on this computer.</p>
    <ol><li>Choose the Linux account used for read-only discovery. A dedicated low-privilege account is recommended; root is not required.</li><li>Install the matching public key for that account using your normal server administration process.</li><li>Verify that the same key and account work with your normal SSH client.</li></ol>
    <aside>Passwords, SSH-agent authentication, and interactive passphrases are not supported. VPS Graph never uploads or stores private-key contents.</aside>
  </div>
}

function KnownHostsGuide() {
  return <div className="setup-guide__content">
    <p>VPS Graph connects only when the server key already exists in <code>~/.ssh/known_hosts</code>. This protects against silently connecting to an unexpected server.</p>
    <ol><li>Get the expected SSH fingerprint from your hosting provider or another trusted channel.</li><li>Connect with your normal SSH client:</li></ol>
    <CommandBlock command="ssh <scan-user>@<host>" />
    <ol start={3}><li>Compare the displayed fingerprint with the trusted value before accepting it.</li><li>Return to VPS Graph and scan again.</li></ol>
    <aside>If the identity changed, stop and verify why. VPS Graph never accepts, removes, or replaces a host key automatically. <code>ssh-keyscan</code> output alone does not authenticate a server.</aside>
  </div>
}

function HelperGuide() {
  return <div className="setup-guide__content">
    <p>The optional helper is an argument-free executable on the VPS. It runs only during a scan. There is no daemon, network listener, unrestricted sudo, Docker-group membership, or Docker socket access for the SSH user.</p>
    <aside>Obtain and review the <code>server-helper</code> directory from the same official VPS Graph release source. The final packaged download path will be supplied during release packaging.</aside>
    <ol><li>On the VPS, ensure Linux, <code>/usr/bin/python3</code>, Docker, and <code>visudo</code> are available.</li><li>From the reviewed helper directory, install it for the account entered in VPS Graph:</li></ol>
    <CommandBlock command={HELPER_INSTALL_COMMANDS[0]} />
    <CommandBlock command={HELPER_INSTALL_COMMANDS[1]} />
    <ol start={3}><li>Verify that sudo grants only the exact helper command and then run that argument-free command:</li></ol>
    <CommandBlock command={HELPER_INSTALL_COMMANDS[2]} />
    <CommandBlock command={HELPER_INSTALL_COMMANDS[3]} />
    <p>The installed path is <code>{HELPER_PATH}</code>, owned by root with mode <code>0755</code>. The generated sudoers rule is root-owned with mode <code>0440</code> and permits no arguments.</p>
    <ol start={4}><li>Return to VPS Graph and choose <strong>Rescan</strong>. No hidden retry or installation runs from the plugin.</li></ol>
  </div>
}

function CommandBlock({ command }: { command: string }) {
  const [copyState, setCopyState] = useState<'IDLE' | 'COPIED' | 'FAILED'>('IDLE')
  const copy = async () => {
    try { await navigator.clipboard.writeText(command); setCopyState('COPIED') }
    catch { setCopyState('FAILED') }
  }
  return <div className="command-block"><code>{command}</code><button type="button" onClick={copy} aria-label={`Copy command: ${command}`}><Clipboard size={14} /> {copyState === 'COPIED' ? 'Copied' : copyState === 'FAILED' ? 'Select manually' : 'Copy'}</button></div>
}
