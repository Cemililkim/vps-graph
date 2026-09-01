# Privacy

VPS Graph has no account, product backend, telemetry, analytics, advertising, or cloud sync. The plugin connects directly from the IDE to the SSH target selected by the user.

When **Remember this connection** is enabled, VPS Graph stores these safe form fields in JetBrains application settings:

- host
- SSH port
- username
- local private-key file path

Local snapshot and history files contain bounded, sanitized infrastructure metadata returned by the scanners. They may include hostnames, addresses, service names, container names, mount paths, routing domains, and relationship history. They remain in the JetBrains IDE system directory on the local computer.

The embedded frontend stores only the selected theme, a successful-scan onboarding flag, and a width-guidance dismissal flag. These values contain no server identity or infrastructure metadata.

VPS Graph does not store private-key contents, passwords, passphrases, raw SSH command output, raw Docker inspect data, raw Caddy configuration, environment variables, or container and service secrets. Disconnect does not delete remembered fields or history; those are separate local data controls.

The operating system, JetBrains Platform, SSH server, and VPS provider may have their own logging and data-handling behavior outside VPS Graph's control.
