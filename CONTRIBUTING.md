# Contributing to VPS Graph

VPS Graph welcomes focused bug fixes, tests, documentation, and carefully scoped discovery improvements.

## Requirements

- JDK 21
- Node.js with npm
- Python 3
- IntelliJ IDEA 2025.1 for sandbox testing

## Repository layout

- `scanner-core/`: IntelliJ-independent Kotlin discovery, parsing, graph, snapshot, and diff logic
- `intellij-plugin/`: Tool Window, JCEF bridge, local preferences, and scan coordination
- `graph-ui/`: React and TypeScript interface bundled into the plugin
- `server-helper/`: argument-free, privileged Linux inspection helper

## Local checks

```text
npm --prefix graph-ui ci
npm --prefix graph-ui test
npm --prefix graph-ui run build
./gradlew :scanner-core:test
./gradlew :intellij-plugin:test
./gradlew :intellij-plugin:buildPlugin
./gradlew :intellij-plugin:verifyPlugin
python -m unittest discover -s server-helper/tests -v
python -m py_compile server-helper/vpsgraph_docker_scan.py
```

On Windows, use `gradlew.bat`. Run `./gradlew :intellij-plugin:runIde` for an interactive sandbox when the change affects the Tool Window.

## Security boundaries

Keep SSH commands fixed and read-only, host-key verification fail-closed, JCEF requests allowlisted, and helper output strictly sanitized. Never expose Docker socket access, arbitrary sudo, arbitrary remote commands, raw helper output, secrets, environment variables, or private-key contents. Any change that weakens or expands these boundaries requires explicit security review and must explain why the expansion is necessary.

## Pull requests

Keep changes focused, add the smallest meaningful regression test, and report commands actually run. Do not include real server names, addresses, credentials, snapshots, logs, or local paths. User-facing behavior changes should include narrow and dark/light checks when relevant.
