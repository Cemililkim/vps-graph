# Security policy

## Supported versions

Security fixes are provided for the latest published 0.1.x release. Pre-release builds and older snapshots are not supported release lines.

## Reporting a vulnerability

Do not open a public issue for a vulnerability or attach credentials, private keys, raw server configuration, or sensitive snapshot data.

Report vulnerabilities privately to [cemililkimteke5934@gmail.com](mailto:cemililkimteke5934@gmail.com). Include the affected plugin version, impact, safe reproduction steps, and a minimal sanitized proof of concept.

Reports are especially important when they involve:

- SSH host-key verification bypass or authentication material exposure
- privilege escalation, sudoers bypass, helper command escape, or Docker socket exposure
- secret leakage from Docker, Caddy, Systemd, listeners, snapshots, logs, or errors
- arbitrary local file access, snapshot path traversal, or unsafe archive handling
- arbitrary remote command execution or expansion of the fixed command allowlist
- JCEF bridge, navigation, or popup escape

Do not test against infrastructure you do not own or have permission to assess.
