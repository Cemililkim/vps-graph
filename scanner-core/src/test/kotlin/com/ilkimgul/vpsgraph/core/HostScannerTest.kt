package com.ilkimgul.vpsgraph.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostScannerTest {
    @Test fun `invalid targets are rejected before execution`() {
        FakeExecutor.called = false
        listOf("" to 22, "host" to 0, "host" to 70000).forEach { (host, port) ->
            assertIs<ScanResult.Failure>(SshTarget.create(host, port, "user", "key"))
        }
        assertIs<ScanResult.Failure>(SshTarget.create("host", 22, "", "key"))
        assertIs<ScanResult.Failure>(SshTarget.create("host", 22, "user", ""))
        assertFalse(FakeExecutor.called)
    }

    @Test fun `valid hostname IPv4 and IPv6 targets remain literal`() {
        listOf("vps.example", "203.0.113.10", "2001:db8::10").forEach { host ->
            assertEquals(host, assertIs<ScanResult.Success<SshTarget>>(SshTarget.create(host, 22, "user", "key")).value.host)
        }
    }

    @Test fun `fixed command allowlist is used and host becomes a server graph`() {
        val executor = FakeExecutor()
        val target = target()
        val scan = HostScanner(executor).scan(target)
        val host = assertIs<ScanResult.Success<HostInfo>>(scan).value
        val graph = HostGraphFactory.create(host, target)

        assertEquals(RemoteCommand.hostDiscoveryCommands, executor.commands)
        assertEquals("server:scanned", graph.nodes.single().id)
        assertEquals(NodeType.SERVER, graph.nodes.single().type)
        assertEquals("vps-prod-01", graph.nodes.single().metadata["hostname"])
        assertFalse(graph.nodes.single().metadata.containsKey("private key"))
    }

    @Test fun `partial optional discovery is retained`() {
        val results = FakeExecutor.results().map {
            if (it.command == RemoteCommand.OS_RELEASE) it.copy(exitCode = 1) else it
        }
        val result = HostScanner(FakeExecutor(results)).scan(target())
        assertEquals(ScanErrorCode.HOST_DISCOVERY_PARTIAL, assertIs<ScanResult.Success<HostInfo>>(result).warning)
    }

    @Test fun `transport failures map without technical detail`() {
        val mapped = SshErrorMapper.map(SocketTimeoutException("private key path"), null)
        assertEquals(ScanErrorCode.SSH_TIMEOUT, mapped.code)
        assertFalse(mapped.userMessage.contains("private key"))
    }

    @Test fun `connection failures use fixed actionable categories without raw exception text`() {
        val failures = listOf(
            UnknownHostException("secret-host") to ScanErrorCode.SSH_HOST_UNREACHABLE,
            NoRouteToHostException("secret-route") to ScanErrorCode.SSH_HOST_UNREACHABLE,
            ConnectException("secret-refusal") to ScanErrorCode.SSH_CONNECTION_REFUSED,
            SocketTimeoutException("secret-timeout") to ScanErrorCode.SSH_TIMEOUT,
        )
        failures.forEach { (failure, code) ->
            val mapped = SshErrorMapper.map(failure, null)
            assertEquals(code, mapped.code)
            assertFalse(mapped.userMessage.contains("secret"))
        }
    }

    @Test fun `private key paths fail locally before SSH and never enter messages`() {
        val existing = Files.createTempFile("vps-graph-key-", ".test")
        try {
            assertNull(validatePrivateKeyPath(existing.toString()))
            assertEquals(ScanErrorCode.SSH_KEY_NOT_FOUND, validatePrivateKeyPath(existing.resolveSibling("missing").toString())?.code)
            assertEquals(ScanErrorCode.SSH_KEY_INVALID, validatePrivateKeyPath("\u0000invalid")?.code)
            assertFalse(validatePrivateKeyPath(existing.resolveSibling("missing").toString())!!.userMessage.contains("missing"))
        } finally {
            Files.deleteIfExists(existing)
        }
    }

    @Test fun `hostname timeout fails safely while optional command loss stays partial`() {
        val timedOut = FakeExecutor.results().map { if (it.command == RemoteCommand.HOSTNAME) it.copy(exitCode = -1, timedOut = true) else it }
        assertEquals(ScanErrorCode.SSH_TIMEOUT, assertIs<ScanResult.Failure>(HostScanner(FakeExecutor(timedOut)).scan(target())).error.code)

        val oversizedOptional = FakeExecutor.results().map { if (it.command == RemoteCommand.OS_RELEASE) it.copy(exitCode = -1, outputLimitExceeded = true) else it }
        assertEquals(ScanErrorCode.HOST_DISCOVERY_PARTIAL, assertIs<ScanResult.Success<HostInfo>>(HostScanner(FakeExecutor(oversizedOptional)).scan(target())).warning)
    }

    @Test fun `known host rejections are classified`() {
        assertEquals(ScanErrorCode.SSH_HOST_KEY_UNKNOWN, SshErrorMapper.map(Exception(), HostKeyRejection.UNKNOWN).code)
        assertEquals(ScanErrorCode.SSH_HOST_KEY_MISMATCH, SshErrorMapper.map(Exception(), HostKeyRejection.MISMATCH).code)
    }

    @Test fun `host and Docker helper share one fixed executor call`() {
        val results = FakeExecutor.results() + CommandResult(RemoteCommand.DOCKER_HELPER, "{\"schemaVersion\":1,\"ok\":true,\"engine\":{\"version\":\"29\"},\"containers\":[]}", "", 0)
        val executor = FakeExecutor(results)
        val scan = HostScanner(executor).scanWithDocker(target())

        assertIs<ScanResult.Success<HostDiscoveryResult>>(scan)
        assertEquals(RemoteCommand.hostDiscoveryCommands + RemoteCommand.DOCKER_HELPER, executor.commands)
    }

    private fun target(): SshTarget = assertIs<ScanResult.Success<SshTarget>>(
        SshTarget.create("vps.example", 22, "deploy", "C:/keys/vps"),
    ).value

    private class FakeExecutor(private val result: List<CommandResult> = results()) : SshExecutor {
        var commands: List<RemoteCommand> = emptyList()
        override fun execute(target: SshTarget, commands: List<RemoteCommand>, onAuthenticated: () -> Unit): ScanResult<List<CommandResult>> {
            this.commands = commands
            called = true
            onAuthenticated()
            return ScanResult.Success(result)
        }

        companion object {
            var called = false
            fun results() = listOf(
                CommandResult(RemoteCommand.HOSTNAME, "vps-prod-01\n", "", 0),
                CommandResult(RemoteCommand.OS_RELEASE, "NAME=Debian\nVERSION_ID=12\n", "", 0),
                CommandResult(RemoteCommand.KERNEL_NAME, "Linux\n", "", 0),
                CommandResult(RemoteCommand.KERNEL_VERSION, "6.1.0\n", "", 0),
                CommandResult(RemoteCommand.ARCHITECTURE, "x86_64\n", "", 0),
            )
        }
    }
}
