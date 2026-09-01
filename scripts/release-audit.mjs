import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join, relative, resolve, sep } from 'node:path'
import { spawnSync } from 'node:child_process'

const root = resolve(import.meta.dirname, '..')
const ignored = new Set(['.git', '.gradle', '.idea', '.intellijPlatform', '.kotlin', '.playwright-cli', 'build', 'dist', 'node_modules', 'output', 'release-staging'])
const textExtensions = new Set(['.css', '.html', '.js', '.json', '.kt', '.kts', '.md', '.py', '.sh', '.ts', '.tsx', '.xml', '.yml', '.yaml'])
const allowedSyntheticSecrets = [
  'server-helper/fixtures/',
  'server-helper/tests/',
  'scanner-core/src/test/resources/docker/helper-success.json',
  'graph-ui/tests/resource-model.test.ts',
  'server-helper/vpsgraph_docker_scan.py',
]
const failures = []

function walk(directory, files = []) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignored.has(entry.name)) continue
    const path = join(directory, entry.name)
    if (entry.isDirectory()) walk(path, files)
    else files.push(path)
  }
  return files
}

function projectPath(path) { return relative(root, path).split(sep).join('/') }
function fail(message) { failures.push(message) }
function read(path) { return readFileSync(join(root, path), 'utf8') }
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', shell: false })
  if (result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.stderr || result.stdout || `exit ${result.status}`}`)
  return result.stdout
}

const files = walk(root)
for (const path of files) {
  const name = projectPath(path)
  if (name === 'LICENSE' || name === 'graph-ui/package-lock.json' || !textExtensions.has(name.slice(name.lastIndexOf('.')))) continue
  if (name === 'scripts/release-audit.mjs') continue
  const content = readFileSync(path, 'utf8')
  if (content.includes('\u2014')) fail(`${name}: user-facing em dash`)
  if (/(?:[A-Za-z]:\\Users\\|\/home\/ilkim(?:\/|\b)|support@example\.invalid|apollyn\.com|ilkim\.dev)/i.test(content)) fail(`${name}: developer-specific path, invalid placeholder, or non-example host`)
  if (/-----BEGIN (?:OPENSSH|RSA|EC) PRIVATE KEY-----/.test(content)) fail(`${name}: private-key material marker`)
  const isAllowedFixture = allowedSyntheticSecrets.some((prefix) => name.startsWith(prefix))
  if (!isAllowedFixture && /^(?:[A-Z0-9_]*(?:PASSWORD|TOKEN|SECRET|API_KEY|DATABASE_URL)[A-Z0-9_]*)\s*=\s*\S+/m.test(content)) fail(`${name}: credential-like assignment`)
}

const versions = {
  gradle: read('build.gradle.kts').match(/version = "([^"]+)"/)?.[1],
  frontend: JSON.parse(read('graph-ui/package.json')).version,
  changelog: read('CHANGELOG.md').match(/## \[([^\]]+)\]/)?.[1],
  marketplace: read('marketplace/METADATA.md').match(/\*\*Version:\*\* `([^`]+)`/)?.[1],
}
if (new Set(Object.values(versions)).size !== 1 || Object.values(versions).some((value) => !value)) fail(`version mismatch: ${JSON.stringify(versions)}`)

const pluginXml = read('intellij-plugin/src/main/resources/META-INF/plugin.xml')
for (const expected of ['<id>com.ilkimgul.vpsgraph</id>', '<name>VPS Graph</name>', '<category>Tools Integration</category>']) if (!pluginXml.includes(expected)) fail(`plugin.xml missing ${expected}`)
if (/example\.invalid|TODO/i.test(pluginXml)) fail('plugin.xml contains a distributable placeholder')

const helperPath = '/usr/local/libexec/vpsgraph-docker-scan'
for (const path of ['server-helper/install.sh', 'server-helper/README.md', 'docs/GETTING_STARTED.md']) if (!read(path).includes(helperPath)) fail(`${path}: helper path mismatch`)

function inspectPlugin(zipPath) {
  if (!existsSync(zipPath)) return fail(`plugin ZIP missing: ${zipPath}`)
  const entries = run('jar', ['tf', zipPath]).trim().split(/\r?\n/)
  for (const required of ['intellij-plugin/lib/intellij-plugin-0.1.0.jar', 'intellij-plugin/lib/scanner-core-0.1.0.jar']) if (!entries.includes(required)) fail(`plugin ZIP missing ${required}`)
  for (const entry of entries) if (/(?:^|\/)(?:tests?|snapshots?|node_modules|__pycache__)(?:\/|$)|\.map$|\.env(?:\.|$)|\.pyc$/i.test(entry)) fail(`unexpected plugin ZIP entry: ${entry}`)
  const directory = mkdtempSync(join(tmpdir(), 'vps-graph-plugin-'))
  try {
    run('jar', ['xf', resolve(zipPath)], directory)
    const pluginJar = join(directory, 'intellij-plugin', 'lib', 'intellij-plugin-0.1.0.jar')
    const jarEntries = run('jar', ['tf', pluginJar]).trim().split(/\r?\n/)
    if (!jarEntries.includes('META-INF/LICENSE') || !jarEntries.includes('META-INF/THIRD_PARTY_NOTICES.md')) fail('plugin ZIP is missing license material')
    for (const entry of jarEntries) {
      if (entry.startsWith('META-INF/licenses/npm/')) continue
      if (/(?:^|\/)(?:tests?|snapshots?|__pycache__)(?:\/|$)|\.(?:map|pyc|kt|tsx?)$/i.test(entry)) fail(`unexpected plugin JAR entry: ${entry}`)
    }
    const jarDirectory = join(directory, 'jar-content')
    mkdirSync(jarDirectory)
    run('jar', ['xf', pluginJar], jarDirectory)
    for (const path of walk(jarDirectory)) {
      if (statSync(path).size > 8_000_000 || !/\.(?:css|html|js|json|md|txt|xml)$/i.test(path)) continue
      const content = readFileSync(path, 'utf8')
      if (/(?:[A-Za-z]:\\Users\\|\/home\/ilkim(?:\/|\b)|apollyn\.com|ilkim\.dev|-----BEGIN (?:OPENSSH|RSA|EC) PRIVATE KEY-----)/i.test(content)) fail(`plugin ZIP text audit failed: ${basename(path)}`)
    }
  } finally { rmSync(directory, { recursive: true, force: true }) }
}

function inspectHelper(archivePath) {
  if (!existsSync(archivePath)) return fail(`helper archive missing: ${archivePath}`)
  const version = versions.gradle
  const prefix = `vps-graph-server-helper-${version}/`
  const expected = ['README.md', 'install.sh', 'uninstall.sh', 'vpsgraph_docker_scan.py'].map((name) => `${prefix}${name}`).sort()
  const entries = run('tar', ['-tzf', archivePath]).trim().split(/\r?\n/).filter((entry) => entry !== prefix).sort()
  if (JSON.stringify(entries) !== JSON.stringify(expected)) fail(`helper archive contents differ: ${JSON.stringify(entries)}`)
  const checksumPath = `${archivePath}.sha256`
  if (!existsSync(checksumPath)) fail(`helper checksum missing: ${checksumPath}`)
  else {
    const expectedHash = readFileSync(checksumPath, 'utf8').trim().split(/\s+/)[0]
    const actualHash = createHash('sha256').update(readFileSync(archivePath)).digest('hex')
    if (expectedHash !== actualHash) fail('helper archive checksum mismatch')
  }
}

const args = process.argv.slice(2)
const pluginIndex = args.indexOf('--plugin-zip')
const helperIndex = args.indexOf('--helper-archive')
if (pluginIndex >= 0) inspectPlugin(resolve(root, args[pluginIndex + 1]))
if (helperIndex >= 0) inspectHelper(resolve(root, args[helperIndex + 1]))

if (failures.length) {
  console.error(`Release audit failed (${failures.length}):\n${failures.map((value) => `- ${value}`).join('\n')}`)
  process.exit(1)
}
console.log(`Release audit passed for VPS Graph ${versions.gradle}${pluginIndex >= 0 ? ', plugin ZIP inspected' : ''}${helperIndex >= 0 ? ', helper archive inspected' : ''}.`)
