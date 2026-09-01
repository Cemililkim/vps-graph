# Security model

VPS Graph is read-only by design. Its release security boundary is:

- **Local-first:** the IDE connects directly to the VPS. There is no VPS Graph account, cloud backend, telemetry, analytics, or cloud sync.
- **Public-key SSH only:** passwords, SSH-agent authentication, and interactive passphrase UX are not supported. The selected private key is read locally and never uploaded or persisted by VPS Graph.
- **Strict host verification:** only `${user.home}/.ssh/known_hosts` is loaded. Unknown or mismatched host keys fail closed; the plugin never trusts, replaces, or writes a key automatically.
- **Fixed read-only commands:** the scanner can request only its compiled hostname, OS, kernel, architecture, and exact helper commands. React and JCEF cannot provide shell commands.
- **Narrow privileged helper:** deeper discovery uses only `sudo -n /usr/local/libexec/vpsgraph-docker-scan` with no arguments. The helper validates its invocation and performs fixed read-only inspection.
- **No broad Docker privilege:** the scan user requires neither unrestricted sudo, Docker-group membership, nor Docker socket access.
- **Sanitized output:** Docker inspect data, Caddy configuration, command output, environment variables, credentials, process arguments, private-key material, and unknown fields are not passed to the UI or snapshots.
- **No remote writes:** the plugin and helper do not install packages, change configuration, restart services, manage containers, or modify files on the VPS.
- **Local persistence only:** remembered connections may store host, port, username, and the user-supplied private-key file path. Snapshot history stores bounded sanitized infrastructure metadata in the JetBrains IDE system directory. Frontend UI storage contains only the selected theme, a successful-scan onboarding flag, and a width-guidance dismissal flag. Passwords, passphrases, private-key contents, raw helper output, and raw SSH output are never persisted.
- **Bounded execution:** SSH and helper commands use finite timeouts and bounded output. A failed rescan does not replace the last successful graph or create a false removal snapshot.

The optional server helper is an executable invoked for a scan, not a daemon or network service. Its installer creates one exact sudoers rule and does not modify `/etc/sudoers`, group membership, Docker, or system services. Review the complete privilege and sanitization boundary in [server-helper/README.md](../server-helper/README.md).

## User responsibilities

Users must independently verify a VPS host fingerprint before accepting it in their normal SSH client, protect local key files, review the helper before privileged installation, and keep the installed helper synchronized with the trusted plugin release. VPS Graph does not weaken these controls to make setup automatic.
