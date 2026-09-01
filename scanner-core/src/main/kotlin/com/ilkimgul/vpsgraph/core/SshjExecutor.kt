package com.ilkimgul.vpsgraph.core

import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import net.schmizz.sshj.userauth.UserAuthException

class SshjExecutor : SshExecutor {
    @Volatile private var activeClient: SSHClient? = null

    override fun execute(
        target: SshTarget,
        commands: List<RemoteCommand>,
        onAuthenticated: () -> Unit,
    ): ScanResult<List<CommandResult>> {
        validatePrivateKeyPath(target.privateKeyPath)?.let { return ScanResult.Failure(it) }
        val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts")
        if (!knownHosts.isFile || !knownHosts.canRead()) {
            return ScanResult.Failure(
                ScanError(ScanErrorCode.SSH_HOST_KEY_UNKNOWN, "The OpenSSH known_hosts file is missing or unreadable. Verify the host key with your normal SSH client."),
            )
        }
        var verifier: TrackingKnownHostsVerifier? = null
        return try {
            verifier = TrackingKnownHostsVerifier(knownHosts)
            SSHClient().use { client ->
                activeClient = client
                client.setConnectTimeout(10_000)
                client.setTimeout(10_000)
                client.addHostKeyVerifier(verifier)
                client.connect(target.host, target.port)
                val key = try {
                    client.loadKeys(target.privateKeyPath)
                } catch (error: Exception) {
                    return ScanResult.Failure(
                        ScanError(ScanErrorCode.SSH_KEY_UNSUPPORTED, "Use a supported unencrypted private key.", error),
                    )
                }
                try {
                    client.authPublickey(target.username, key)
                } catch (error: UserAuthException) {
                    return ScanResult.Failure(ScanError(ScanErrorCode.SSH_AUTH_FAILED, "Public-key authentication failed.", error))
                }
                onAuthenticated()
                client.setTimeout(15_000)
                val results = commands.map { command ->
                    client.startSession().use { session ->
                        val remote = session.exec(command.wireValue)
                        remote.join(15, TimeUnit.SECONDS)
                        if (remote.exitStatus == null) {
                            runCatching { remote.close() }
                            CommandResult(command, "", "", -1, timedOut = true)
                        } else {
                            val stdout = remote.inputStream.readBounded(MAX_STDOUT_BYTES)
                            val stderr = remote.errorStream.readBounded(MAX_STDERR_BYTES)
                            CommandResult(
                                command = command,
                                stdout = stdout.orEmpty(),
                                stderr = stderr.orEmpty(),
                                exitCode = remote.exitStatus ?: -1,
                                outputLimitExceeded = stdout == null || stderr == null,
                            )
                        }
                    }
                }
                ScanResult.Success(results)
            }
        } catch (error: Exception) {
            ScanResult.Failure(SshErrorMapper.map(error, verifier?.rejection))
        } finally {
            activeClient = null
        }
    }

    override fun close() {
        activeClient?.close()
    }

}

internal fun validatePrivateKeyPath(value: String): ScanError? {
    val path = try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        return ScanError(ScanErrorCode.SSH_KEY_INVALID, "The private-key path is invalid. Choose a regular private-key file.")
    }
    if (!Files.isRegularFile(path)) {
        return ScanError(ScanErrorCode.SSH_KEY_NOT_FOUND, "The selected private-key file was not found. Choose an existing regular file.")
    }
    if (!Files.isReadable(path)) {
        return ScanError(ScanErrorCode.SSH_KEY_UNREADABLE, "The selected private-key file is not readable by the IDE process.")
    }
    return null
}

enum class HostKeyRejection { UNKNOWN, MISMATCH }

class TrackingKnownHostsVerifier(file: File) : OpenSSHKnownHosts(file) {
    var rejection: HostKeyRejection? = null
        private set

    override fun hostKeyUnverifiableAction(hostname: String, key: java.security.PublicKey): Boolean {
        rejection = HostKeyRejection.UNKNOWN
        return false
    }

    override fun hostKeyChangedAction(hostname: String, key: java.security.PublicKey): Boolean {
        rejection = HostKeyRejection.MISMATCH
        return false
    }
}

internal object SshErrorMapper {
    fun map(error: Exception, rejection: HostKeyRejection?): ScanError = when (rejection) {
        HostKeyRejection.MISMATCH -> ScanError(
            ScanErrorCode.SSH_HOST_KEY_MISMATCH,
            "The SSH host key does not match known_hosts. Verify it with your normal SSH client.",
            error,
        )
        HostKeyRejection.UNKNOWN -> ScanError(
            ScanErrorCode.SSH_HOST_KEY_UNKNOWN,
            "The SSH host key is not in known_hosts. Verify and add it with your normal SSH client.",
            error,
        )
        null -> when {
            error.hasCause<UserAuthException>() -> ScanError(ScanErrorCode.SSH_AUTH_FAILED, "Public-key authentication failed. Check the username and authorized key.", error)
            error.hasCause<SocketTimeoutException>() -> ScanError(ScanErrorCode.SSH_TIMEOUT, "The SSH connection timed out. Check the host, port, and network path.", error)
            error.hasCause<UnknownHostException>() -> ScanError(ScanErrorCode.SSH_HOST_UNREACHABLE, "The SSH host name could not be resolved. Check the host name and DNS configuration.", error)
            error.hasCause<NoRouteToHostException>() -> ScanError(ScanErrorCode.SSH_HOST_UNREACHABLE, "The SSH host is unreachable. Check the address, network, and port.", error)
            error.hasCause<ConnectException>() -> ScanError(ScanErrorCode.SSH_CONNECTION_REFUSED, "The SSH connection was refused. Check the SSH port and server service.", error)
            error is ConnectionException || error is TransportException -> ScanError(ScanErrorCode.SSH_CONNECTION_FAILED, "The SSH connection failed. Check the host, port, and network access.", error)
            else -> ScanError(ScanErrorCode.REMOTE_COMMAND_FAILED, "Host discovery could not be completed.", error)
        }
    }
}

private fun InputStream.readBounded(limit: Int): String? {
    val bytes = readNBytes(limit + 1)
    return bytes.takeIf { it.size <= limit }?.toString(StandardCharsets.UTF_8)
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence<Throwable>(this) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any { it is T }

private const val MAX_STDOUT_BYTES = 8 * 1024 * 1024
private const val MAX_STDERR_BYTES = 64 * 1024
