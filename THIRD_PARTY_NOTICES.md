# Third-party software

VPS Graph is licensed under Apache License 2.0. It uses the following third-party software at runtime.

| Component | License | Distribution note |
| --- | --- | --- |
| Kotlin standard library, kotlinx.serialization, SSHJ, ASN.1 utilities, JetBrains annotations | Apache-2.0 | License text is included in this distribution. |
| Bouncy Castle Java libraries | Bouncy Castle Licence | Permissive license text is carried in the upstream JARs. |
| SLF4J API | MIT | License text is carried in the upstream JAR. |
| React, React DOM, React Flow (`@xyflow/react`), Zustand, classcat, csstype | MIT | The corresponding package license files are included in the plugin distribution. |
| Lucide React | ISC | The corresponding package license file is included in the plugin distribution. |
| D3 packages used by React Flow | ISC or BSD-3-Clause | The corresponding package license files are included in the plugin distribution. |

Vite, TypeScript, and related frontend tools are build-time dependencies and are not shipped as executable plugin dependencies. The IntelliJ Platform is supplied by the host IDE and is not redistributed by VPS Graph.

The dependency audit for version 0.1.0 found no copyleft or unlicensed runtime component. No upstream runtime artifact inspected for this release supplied a mandatory NOTICE file. Exact npm package license files are packaged under `META-INF/licenses/npm/`.
