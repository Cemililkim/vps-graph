# Dependency license audit for 0.1.0

This audit covers declared dependencies, resolved JVM runtime dependencies, the npm lockfile, bundled frontend code, the host-provided IntelliJ Platform, and the Python helper.

## Bundled JVM runtime

| Dependency family | License | Apache-2.0 compatibility | Notice handling |
| --- | --- | --- | --- |
| Kotlin standard library and kotlinx.serialization | Apache-2.0 | Compatible | Root Apache-2.0 text included |
| SSHJ 0.40.0 and `asn-one` | Apache-2.0 | Compatible | Root Apache-2.0 text included |
| Bouncy Castle provider, PKIX, and utility libraries | Bouncy Castle Licence | Compatible permissive license | License carried in upstream JARs |
| SLF4J API | MIT | Compatible | License carried in upstream JAR |
| JetBrains annotations | Apache-2.0 | Compatible | Root Apache-2.0 text included |

The inspected runtime JARs did not supply a mandatory upstream NOTICE file. VPS Graph therefore does not create an attribution-only NOTICE file.

## Bundled frontend runtime

| Dependency family | License | Apache-2.0 compatibility |
| --- | --- | --- |
| React, React DOM, scheduler | MIT | Compatible |
| React Flow (`@xyflow/react` and `@xyflow/system`) | MIT | Compatible |
| Zustand, classcat, csstype, React type utilities | MIT | Compatible |
| Lucide React | ISC | Compatible |
| D3 packages used by React Flow | ISC or BSD-3-Clause | Compatible |

All corresponding npm package license files are copied into the plugin under `META-INF/licenses/npm/`. The production bundle contains no remote font, icon, or other downloaded visual asset.

## Build and development dependencies

Vite, its React plugin, Rollup, esbuild, Babel tooling, PostCSS, and most related packages are MIT-licensed. TypeScript is Apache-2.0. The lockfile also contains ISC and BSD-3-Clause packages plus CC-BY-4.0 browser-support data used only by build tooling. These packages are not shipped as executable plugin dependencies; generated frontend output is covered by the included upstream runtime license files.

The lockfile license inventory at audit time was: 120 MIT, 14 ISC, 2 Apache-2.0, 2 BSD-3-Clause, and 1 CC-BY-4.0 package. The lockfile remains the authoritative exact dependency/version inventory.

## Platform and helper

The IntelliJ Platform is provided by the installed IDE rather than redistributed in the plugin ZIP. The IntelliJ Platform Gradle Plugin and Gradle are build tools. The server helper imports only Python standard-library modules and does not bundle Python or any pip dependency.

## Conclusion

No copyleft, proprietary-only, unknown-license, or unlicensed runtime dependency was found. Apache License 2.0 is compatible with the redistributable project components audited for version 0.1.0. Publisher identity and copyright-holder wording remain a human release decision; no identity was inferred from package names.
