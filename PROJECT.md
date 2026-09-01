# VPS Graph

## 1. Product Summary

VPS Graph is a read-only infrastructure visualization tool for developers and small teams who inspect one Linux VPS at a time.

The product connects to a server over SSH, discovers its relevant infrastructure, normalizes that information into a structured model, and renders the server architecture as an interactive graph.

The primary goal is simple:

> Connect to a server and understand what is actually running, where it lives, who owns it, and how the pieces are connected.

VPS Graph is **not** a server control panel, monitoring platform, deployment platform, or infrastructure-as-code replacement.

It is an **infrastructure understanding and documentation tool**.

---

# 2. Problem

A VPS often evolves organically.

Over time, the owner may have:

* multiple Docker Compose projects
* Caddy or Nginx reverse proxy rules
* systemd services
* manually installed packages
* multiple Linux users and groups
* application directories spread across the filesystem
* ports exposed by different services
* different ownership and permission rules
* stale services or containers
* environment/configuration files
* forgotten deployment conventions

After several months, answering simple questions becomes surprisingly difficult:

* What is actually running on this server?
* Which domain points to which application?
* Which container exposes this port?
* Where is this application stored?
* Which Linux user owns it?
* What starts this service?
* Which reverse proxy routes traffic to it?
* Is this service managed by Docker or systemd?
* What changed since last month?
* Why does this directory exist?
* What breaks if this service disappears?

Traditional monitoring tools mostly answer:

> Is the server healthy?

VPS Graph should answer:

> How is the server structured?

---

# 3. Target Users

Initial target:

* solo developers
* indie hackers
* freelancers
* small agencies
* developers managing 1–10 VPS instances
* self-hosting enthusiasts
* developers deploying Dockerized web applications

The initial audience is intentionally not:

* large DevOps teams
* Kubernetes-heavy organizations
* enterprise infrastructure departments
* managed cloud environments with hundreds of servers

---

# 4. Product Positioning

Primary positioning:

> Understand your server architecture at a glance.

Alternative developer-oriented positioning:

> Connect your VPS. See what the hell is actually running on it.

VPS Graph should feel like:

* a dependency graph for a Linux server
* an architecture map generated from reality
* living infrastructure documentation

It should not feel like:

* Portainer
* Grafana
* Webmin
* Cockpit
* Datadog
* Terraform
* Ansible

---

# 5. Initial Distribution

The first version will be released as a **JetBrains IDE plugin**.

Reasons:

* built-in developer audience
* Marketplace discovery
* familiar IDE installation and update workflow
* no VPS Graph backend or account requirement
* low operational complexity
* natural developer workflow integration

The scanning core must remain independent from JetBrains APIs so that a future standalone desktop application is possible.

Potential future products:

* standalone desktop application
* CLI
* VS Code extension
* shared team dashboard

These are explicitly out of scope for the MVP.

---

# 6. Core Product Principles

## 6.1 Read-only by design

The scanner must not modify the remote server.

The product must never automatically:

* install packages
* restart services
* stop containers
* modify permissions
* modify configuration
* write files
* execute deployment commands

Remote commands in the MVP must be inspection-only.

This is both a security feature and a core marketing advantage.

---

## 6.2 Local-first

Server information should be processed locally whenever practical.

The MVP must not require a VPS Graph backend.

No remote infrastructure data should be uploaded to a third-party VPS Graph server.

The product should work without:

* user account
* SaaS backend
* cloud database
* proprietary API

---

## 6.3 Explicit access

The scanner should collect low-risk metadata by default.

Sensitive file contents must not be automatically collected.

For example:

Allowed automatically:

* service names
* package names
* port mappings
* container metadata
* paths
* ownership
* filesystem permissions
* reverse-proxy route structure

Require explicit user action:

* `.env` contents
* secret files
* application source files
* arbitrary configuration file contents

---

## 6.4 Progressive discovery

The scanner should not try to understand all Linux infrastructure on day one.

MVP support:

* Debian
* Ubuntu
* systemd
* Docker
* Docker Compose
* Caddy
* Linux users/groups
* listening ports
* selected filesystem paths

Later:

* Nginx
* Podman
* PostgreSQL
* MySQL
* Redis
* cron
* firewall rules
* certificates
* fail2ban
* Git repositories
* package dependency relationships

Much later:

* Fedora/RHEL
* Arch
* Kubernetes

---

# 7. MVP User Flow

## Step 1: Open VPS Graph

The user opens the `VPS Graph` JetBrains Tool Window.

Initial state:

* server selector
* Scan Server button
* empty graph canvas

---

## Step 2: Choose SSH target

Initial authentication model:

Use existing local SSH configuration where possible.

Example:

```text
Host production
    HostName 203.0.113.10
    User developer
    IdentityFile ~/.ssh/id_ed25519
```

The user chooses:

`production`

The MVP should prioritize SSH-key authentication.

Password vault functionality is out of scope.

---

## Step 3: Scan

The plugin establishes an SSH connection and executes read-only discovery commands.

Examples:

```bash
uname -a
cat /etc/os-release

getent passwd
getent group

systemctl list-units --type=service
systemctl list-unit-files

ss -tulpn

docker ps
docker inspect ...
docker compose ls
```

Caddy discovery may inspect:

```bash
caddy version
```

and known Caddy configuration paths.

Reading a Caddyfile should require a clearly defined scanner policy because configuration files may contain sensitive values.

---

## Step 4: Normalize

Raw command output is converted to internal domain models.

Example:

```text
Server
User
Group
Directory
File
Package
SystemdService
DockerContainer
DockerComposeProject
Port
Domain
ReverseProxy
```

Relations may include:

```text
OWNS
RUNS
CONTAINS
EXPOSES
ROUTES_TO
STARTED_BY
MEMBER_OF
MOUNTS
READS_FROM
DEPENDS_ON
```

---

## Step 5: Render graph

Example architecture:

```text
Internet
   │
   ▼
example.com
   │
   ▼
Caddy
   │
   ▼
portfolio-container
   │
   ├── port 3000
   │
   ▼
/home/developer/apps/portfolio
```

The graph should support:

* pan
* zoom
* node selection
* edge selection
* automatic layout
* node details
* category icons
* basic filtering

---

# 8. Graph Design

Primary visual style:

* professional
* clean
* dark-friendly
* minimal
* developer-focused

Suggested frontend:

* React
* TypeScript
* `@xyflow/react`
* Lucide icons
* Vite

Avoid excessive gradients and decorative UI.

The graph itself should remain the dominant visual element.

Potential optional future graph themes:

* Professional
* Terminal
* Pixel / Dungeon

Pixel art must remain optional and should not compromise professional positioning.

---

# 9. Node Types

## Server

Information:

* hostname
* operating system
* kernel
* architecture
* uptime

---

## User

Information:

* username
* UID
* home directory
* shell
* groups

---

## Directory

Information:

* path
* owner
* group
* permissions
* size if available

---

## Systemd Service

Information:

* unit name
* state
* enabled state
* executable
* user
* working directory if discoverable

---

## Docker Container

Information:

* name
* image
* state
* ports
* volumes
* networks
* compose project
* restart policy

---

## Docker Compose Project

Information:

* project name
* compose file if discoverable
* working directory
* containers

---

## Reverse Proxy

Initial:

* Caddy

Information:

* domain
* path
* upstream
* destination port/service

---

## Port

Information:

* protocol
* local address
* port
* owning process if available
* public/private exposure when reasonably inferable

---

# 10. Relationship Examples

```text
Caddy ROUTES_TO portfolio-container
portfolio-container EXPOSES port-3000
portfolio-container STARTED_BY portfolio-compose
portfolio-compose LOCATED_IN /home/developer/apps/portfolio
developer OWNS /home/developer/apps
portfolio-container MOUNTS /home/developer/apps/portfolio/uploads
```

Relationships should be first-class entities.

The value of the product is primarily in the relationships, not merely inventory.

---

# 11. Node Inspector

Selecting a node should open an inspector.

Example:

## portfolio

Type: Docker Container

Image:

`portfolio:latest`

Status:

`running`

Compose project:

`portfolio`

Exposed ports:

`3000/tcp`

Reverse proxies:

`example.com → :3000`

Mounted directories:

`/home/developer/apps/portfolio/uploads`

Runs as:

`UID 1000`

Potential actions in MVP:

* copy information
* focus related nodes
* open related local project file if known

No destructive remote actions.

---

# 12. Search and Filtering

The graph should eventually support:

```text
docker
user:developer
port:5432
domain:example.com
type:service
```

MVP can initially support simple text search.

Filters:

* Docker
* systemd
* filesystem
* users
* networking
* reverse proxy

---

# 13. Snapshots

Snapshots are a high-value post-MVP feature.

A snapshot represents the normalized server graph at a point in time.

Example:

`Production, 2026-08-27 14:31`

Future scan:

`Production, 2026-09-14 21:10`

The tool should compare them.

Example:

```text
ADDED
+ user: deploy
+ container: worker

REMOVED
- package: caddy

CHANGED
docker:
  node:22 → node:24

permissions:
/home/developer/apps
750 → 755
```

Snapshots should be stored locally.

---

# 14. Security Model

Security is a core product requirement.

## Requirements

* SSH credentials must never be logged.
* Secret file contents must not be collected automatically.
* `.env` files must be excluded by default.
* private keys must never be read as infrastructure content.
* remote commands must be explicitly defined.
* scanner must not execute arbitrary shell commands generated dynamically by AI.
* scanner command set should be auditable.
* sensitive output should be redacted before logs.
* application logs must avoid raw environment values.

---

# 15. Architecture

High-level structure:

```text
vps-graph/
│
├── scanner-core/
│   ├── model/
│   ├── ssh/
│   ├── collectors/
│   ├── parsers/
│   ├── graph/
│   └── security/
│
├── intellij-plugin/
│   ├── toolwindow/
│   ├── actions/
│   ├── services/
│   ├── settings/
│   └── bridge/
│
└── graph-ui/
    ├── src/
    ├── components/
    ├── nodes/
    ├── graph/
    └── bridge/
```

---

# 16. Technology Stack

## JetBrains plugin

* Kotlin
* IntelliJ Platform SDK
* Gradle Kotlin DSL
* IntelliJ Platform Gradle Plugin 2.x
* Java 17+ compatible toolchain as required by target platform

---

## Graph UI

* React
* TypeScript
* Vite
* `@xyflow/react`
* Lucide icons

---

## Embedded browser

* JCEF

Communication:

```text
Kotlin
  ↕
JCEF bridge
  ↕
React
```

---

## Scanner

Prefer a dedicated JVM SSH implementation.

The scanner API must not depend on IntelliJ classes.

Example:

```kotlin
interface ServerScanner {
    suspend fun scan(target: SshTarget): ServerSnapshot
}
```

---

# 17. Core Data Model

Initial concept:

```kotlin
data class ServerSnapshot(
    val server: ServerInfo,
    val nodes: List<InfraNode>,
    val edges: List<InfraEdge>,
    val scannedAt: Instant
)
```

Example node abstraction:

```kotlin
sealed interface InfraNode {
    val id: String
    val label: String
}
```

Example relation:

```kotlin
data class InfraEdge(
    val id: String,
    val source: String,
    val target: String,
    val relation: RelationType
)
```

The graph UI should receive a serialized representation independent from scanner implementation.

---

# 18. Collector Architecture

Collectors must be independent and composable.

Example:

```kotlin
interface Collector<T> {
    suspend fun collect(context: ScanContext): T
}
```

Initial collectors:

* OsCollector
* UserCollector
* SystemdCollector
* PortCollector
* DockerCollector
* DockerComposeCollector
* CaddyCollector
* FilesystemCollector

Failure of one collector should not necessarily abort the complete scan.

Example:

```text
DockerCollector ✅
SystemdCollector ✅
CaddyCollector ⚠ permission denied
FilesystemCollector ✅
```

The UI should surface partial scan status.

---

# 19. Parser Testing

Parsing remote command output is a major reliability risk.

Every parser must have fixture-based tests.

Example fixtures:

```text
fixtures/
├── docker/
├── systemd/
├── caddy/
├── users/
└── ports/
```

Tests should cover:

* empty output
* malformed output
* missing commands
* permission errors
* multiple tool versions
* unexpected whitespace
* non-English locale when relevant

Prefer machine-readable command output when possible.

---

# 20. Error Handling

Errors should be categorized.

Examples:

```text
SSH_CONNECTION_FAILED
SSH_AUTH_FAILED
COMMAND_NOT_FOUND
COMMAND_PERMISSION_DENIED
UNSUPPORTED_OS
PARSER_FAILED
PARTIAL_SCAN
```

The UI should avoid dumping raw JVM stack traces to users.

Developer logs may contain technical context but not secrets.

---

# 21. First MVP

The first technical milestone is intentionally tiny.

The plugin must:

1. launch inside IntelliJ IDEA
2. show a `VPS Graph` Tool Window
3. load a React UI inside JCEF
4. connect to one SSH target
5. retrieve hostname/OS
6. discover running Docker containers
7. discover one simple Caddy route if available
8. convert results to nodes/edges
9. display those nodes in the graph

Example:

```text
Server
  │
  ├── Caddy
  │      │
  │      ▼
  │   portfolio
  │
  └── Docker
         │
         └── portfolio
```

This milestone validates the three riskiest parts:

```text
SSH
 ↓
Normalized infrastructure model
 ↓
JCEF graph rendering
```

---

# 22. Explicitly Out of Scope for MVP

Do not implement:

* VPS Graph cloud accounts
* multi-user collaboration
* monitoring
* CPU/RAM dashboards
* log streaming
* deployment
* Docker management
* service restart
* terminal replacement
* Kubernetes
* AWS/Azure/GCP scanning
* automatic AI analysis
* arbitrary source-code reading
* `.env` reading
* secret management
* Windows servers
* macOS servers
* Nginx support unless needed after MVP
* mobile application

---

# 23. Public Distribution

VPS Graph launches completely free under an Apache-2.0-compatible public source release. It has no paid tier, licensing service, account, product backend, telemetry, analytics, or cloud sync.

Marketplace distribution and a public source repository make installation and review straightforward without changing the local-first security model. Future capability work must not introduce monetization, accounts, telemetry, or a cloud backend without a separately approved product decision.

---

# 24. Validation Strategy

Public beta validation relies on voluntary issue reports, pull requests, and explicitly submitted feedback. The plugin itself does not collect install activity, scan activity, server data, usage analytics, or feature-intent telemetry.

Release validation covers reproducible builds, supported IntelliJ compatibility, sanitized helper behavior, real Debian acceptance, narrow and preferred-width JCEF layouts, and preservation of all SSH, helper, JCEF, snapshot, and read-only boundaries.

---

# 25. Success Criteria for MVP

Technical success:

* plugin works reliably in supported JetBrains IDE
* SSH scan succeeds on a normal Debian/Ubuntu VPS
* graph renders correctly
* no server modifications occur
* no secrets are collected
* scanner failures degrade gracefully

Product success:

A developer connects a server and within a few minutes says:

> “Oh. So THAT is how this VPS is actually wired together.”

That reaction is the core value of VPS Graph.

---

# 26. Milestone 6A: Canonical Infrastructure Snapshots

Milestone 6 is split deliberately:

* M6A: canonical snapshot contract and local persistence
* M6B: deterministic infrastructure diff engine
* M6C: Changes/history UX

Only M6A is implemented here. A snapshot is an explicit schema-version `1` projection of the sanitized scanner-core `InfraGraph`, not a serialization of raw helper output, SSH output, React state, or runtime objects. Node and edge collections and metadata maps are ordered deterministically. Per-node metadata is selected through a node-type allowlist, so future implementation or UI fields do not silently enter historical identity.

Each snapshot has three distinct identifiers:

* `snapshotId`: a random UUID for one stored capture;
* `serverId`: a locally generated opaque UUID associated with the normalized SSH host/port/username connection identity;
* `graphFingerprint`: SHA-256 over schema semantics and canonical snapshot content.

The private-key path and remote hostname do not participate in the local server identity. Capture time is a local UTC `Instant` and, like the snapshot ID, file path, IDE version, and plugin session, is excluded from the graph fingerprint.

Snapshots retain fixed discovery-quality state and reason codes for Docker, Caddy, Systemd, and host listeners. This provenance is required so M6B can avoid interpreting a failed subsystem discovery as confirmed resource removal. No diff interpretation is implemented in M6A.

The IntelliJ module stores UTF-8 JSON under the IDE system data directory:

```text
vps-graph/snapshots/<opaque-server-uuid>/
```

Each server has a lightweight versioned `index.json` plus one file per snapshot UUID. Writes use a temporary file in the same directory followed by atomic replace where supported. The index is recoverable convenience data: missing, corrupt, or stale indexes are rebuilt from independently valid snapshot files, and one bad file cannot block current scanning or other history.

Persistence is bounded to 32 MiB per snapshot, 25,000 nodes, 100,000 edges, bounded metadata strings/collections, 50 retained snapshots per server, and at most 200 snapshot files inspected during recovery. Identical consecutive graph fingerprints are deduplicated. Repository writes are serialized per local server identity; unrelated servers do not share one global write lock.

Snapshot persistence runs after a successful canonical graph is published. A local persistence error is logged without raw snapshot content and does not turn a successful read-only VPS scan into a failed scan. Remote commands, the helper/sudo boundary, known-host verification, discovery behavior, and JCEF bridge operations are unchanged.

A snapshot is not a VPS backup. It contains only sanitized discovered infrastructure metadata and cannot restore, roll back, or modify infrastructure. Diffing, Changes/history UI, export/import, scheduling, remote storage, cloud sync, and restore remain out of scope for M6A.

---

# 27. Milestone 6B: Deterministic Infrastructure Diff Engine

M6B compares two schema-version `1` snapshots using a pure scanner-core function. It performs no repository access, filesystem work, IntelliJ calls, network requests, logging, or remote commands. M6C, not M6B, owns history and Changes presentation.

Canonical IDs are the only resource-matching rule. Same node IDs are compared for canonical type, label, and allowlisted metadata; different IDs produce removal and addition without fuzzy rename inference. Canonical edge IDs similarly produce deterministic relationship additions/removals rather than invented modified-edge semantics.

The engine explicitly maps current node types to the base-host, Docker, Caddy, Systemd, and host-listener discovery subsystems. Relationship provenance is the union of its endpoint requirements. Absence is evidence of removal only when every required current subsystem is complete. Positive current evidence can still establish presence under partial discovery.

Evidence has three fixed states:

* `CONFIRMED`: complete evidence supports the addition/removal or both values directly support a modification;
* `NEWLY_OBSERVED`: a current resource exists, but previous incomplete discovery could have missed it;
* `UNCONFIRMED_REMOVAL`: the resource is absent from the current snapshot, but incomplete current discovery cannot prove removal.

Current incomplete discovery and previous incomplete evidence used by newly observed additions produce fixed, deterministic uncertainty records. Unknown future quality states are always incomplete. Discovery-quality transitions are context rather than resource changes, and quality metadata fields are excluded from node modifications.

Diff results are deterministically ordered and contain snapshot references, status, evidence-qualified node changes, relationship changes, field changes, uncertainty records, and summary counts. Identical fingerprints take the `NO_CHANGES` fast path. First snapshots return `NO_BASELINE`; cross-server, unsupported-schema, reversed-time, and malformed canonical inputs return typed non-diff statuses.

The implementation uses maps and sets keyed by canonical IDs, with linear indexing and stable output sorting. It does not persist diffs, guess renames, or aggregate servers. Milestone 6C presents its typed results in production without changing these semantics.

## Remembered connection convenience

The plugin may remember one most-recent SSH form through JetBrains application persistent state. Only host, port, username, and private-key file path are stored. The key path does not participate in snapshot server identity, and no key content, passphrase, password, decrypted material, or session credential is stored. Loading the preference only pre-fills the form; it never connects automatically. A missing key remains visible and invalid without fallback discovery. Clearing the checkbox removes only form convenience data, not snapshots, server identity, `known_hosts`, or files.

---

# 28. Milestone 6C: Changes and Snapshot History UX

M6C adds a first-class **Changes** workspace while keeping scanner-core's M6B result authoritative. React performs presentation filtering, safe text formatting, and one narrow deterministic relationship grouping only; it does not match nodes, establish removal evidence, interpret discovery quality, compare fingerprints, or derive `DiffSummary` counts.

The default successful-scan flow is ordered as follows:

1. build and publish the live canonical graph;
2. project the current schema-version `1` snapshot;
3. let the repository persist or deduplicate it;
4. load the immediately previous changed snapshot when available;
5. run `InfrastructureDiffEngine`;
6. expose a typed, sanitized Changes payload through JCEF.

A first capture is `NO_BASELINE` and displays **Baseline captured**, never synthetic additions. An identical live fingerprint is compared with the latest persisted baseline and displays a calm no-change state without adding a duplicate file. Changed and partial comparisons show authoritative M6B counts, changed fields, evidence-qualified node and relationship records, and fixed discovery-uncertainty copy. Confirmed removals appear under Removed; `UNCONFIRMED_REMOVAL` stays under Uncertain; `NEWLY_OBSERVED` explains that previous incomplete discovery cannot establish first appearance.

Relationship removal/addition pairs are visually grouped only for the exact same canonical subject and relation context with one record of each kind. This grouping is deterministic and lossless: Details retains both canonical records. Existing current resources navigate to their normal workspace and Details selection. Removed resources remain separate historical projections and display only allowlisted snapshot metadata; they are never inserted into the immutable live `InfraGraph` or global live-resource search.

History initially sends only opaque snapshot IDs, capture instants, and short diagnostic fingerprints. Historical comparison requests contain two snapshot UUIDs. The plugin validates that both snapshots belong to the active server, loads them through `SnapshotRepository`, enforces chronological comparison, and returns the typed M6B projection. No frontend operation accepts a filesystem path, exposes a repository path, or transfers every retained full snapshot. Missing, corrupt, unsupported, retention-deleted, and wrong-server requests fail with fixed product errors without breaking current infrastructure views.

Times remain canonical UTC `Instant` values in storage and transport and are rendered in the user's local timezone, with exact timestamps in tooltips and Details. The existing 50-changed-snapshot retention and consecutive-fingerprint deduplication policies remain unchanged. There are no delete, pin, export, restore, rollback, apply, notification, scheduling, remote-write, remote-storage, or cloud-sync capabilities.

Remembered connection state remains a separate optional JetBrains application preference. M6C neither stores new SSH data nor changes prefill, missing-key, auto-connect, or auto-scan behavior.

---

# 29. Release Hardening R1

R1 is a release-candidate hardening pass, not a capability milestone. It preserves all SSH, helper/sudo, sanitization, graph identity, snapshot, diff-evidence, and JCEF navigation boundaries established through M6C.

Connection and remote-command failures are mapped to fixed product-owned categories. Private-key paths are validated locally before SSH, command execution has finite timeouts and bounded output, bridge requests are bounded JSON objects, and missing bundled frontend resources produce a native Tool Window error instead of an unmanaged browser failure. Optional Docker, Caddy, Systemd, and listener failures continue to preserve the valid host graph.

The scan coordinator accepts one active scan at a time. Only a successful canonical graph is published and passed to snapshot capture. If a later scan fails, the last successful graph and history remain visible with explicit stale context; no failed snapshot, fake empty graph, or removal inference is produced.

The declared IDE range is limited to the verified IntelliJ IDEA 2025.1 platform (`251.*`). Target-host testing focuses on Debian 12/13 and Ubuntu 22.04/24.04 fixtures plus the existing sanitized real-Debian regression fixtures. Unsupported init systems and optional subsystem failures degrade explicitly without speculative discovery.

The release matrix and manual, non-destructive checklist live in `RELEASE_HARDENING_R1.md`.

---

# 30. Release Phase R2: First-Run Onboarding

R2 adds progressive setup guidance to the existing connection flow without changing scanner, helper, bridge, graph, snapshot, or diff semantics. First-time users see concise public-key SSH, strict `known_hosts`, and optional helper prerequisites; remembered users keep the compact prefilled form and are never auto-connected or auto-scanned. One local frontend boolean suppresses the first-scan checklist after a successful scan; it contains no server identity or infrastructure data.

Connection validation and fixed R1 error codes now map to calm product-owned explanations and one actionable next step. Raw transport exceptions are not displayed. Unknown and changed host keys remain fail-closed and require independent fingerprint verification through the user's normal SSH workflow.

The optional helper guide is derived from the checked-in installer and exact argument-free sudo boundary. It does not install remotely, add users to groups, expose the Docker socket, or create a daemon. Normal scan provenance distinguishes an unavailable helper from Docker or Caddy that is genuinely absent, and reports Systemd/listener capability without adding a separate probe.

The linear setup and release security documentation live in `docs/GETTING_STARTED.md` and `docs/SECURITY_MODEL.md`.

# 31. Release Phase R3: Public Packaging

R3 prepares version `0.1.0` as an initial public beta without changing infrastructure discovery. It adds Apache-2.0-compatible licensing material, public documentation, Marketplace metadata, deterministic helper packaging, release audits, and Linux CI. Publisher identity, security contact, public repository URL, final icon, and sanitized screenshots remain explicit human publication gates.

The approved interface polish is deliberately local: first successful presentation opens Resource Explorer at practical widths, the Tool Window makes one supported default-width request, subtle guidance remains available at significantly narrow widths, and Disconnect returns to Connect without remote commands or local history deletion. Disconnect is disabled during a scan, preventing a completed scan from repopulating a disconnected session.
