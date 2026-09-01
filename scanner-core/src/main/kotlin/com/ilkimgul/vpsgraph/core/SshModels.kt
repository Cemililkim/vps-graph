package com.ilkimgul.vpsgraph.core

import kotlinx.serialization.Serializable

data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val privateKeyPath: String,
) {
    init {
        require(host.isNotBlank()) { "Host is required." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        require(username.isNotBlank()) { "Username is required." }
        require(privateKeyPath.isNotBlank()) { "A private key is required." }
    }

    override fun toString(): String = "SshTarget(host=$host, port=$port, username=$username, privateKeyPath=<redacted>)"

    companion object {
        fun create(host: String, port: Int, username: String, privateKeyPath: String): ScanResult<SshTarget> {
            val normalizedHost = host.trim()
            if (normalizedHost.isEmpty()) return ScanResult.Failure(ScanError.invalidTarget("Host is required."))
            if (port !in 1..65535) return ScanResult.Failure(ScanError.invalidTarget("Port must be between 1 and 65535."))
            if (username.isBlank()) return ScanResult.Failure(ScanError.invalidTarget("Username is required."))
            if (privateKeyPath.isBlank()) return ScanResult.Failure(ScanError.invalidTarget("A private key is required."))

            return ScanResult.Success(SshTarget(normalizedHost, port, username.trim(), privateKeyPath.trim()))
        }
    }
}

enum class RemoteCommand(val wireValue: String) {
    HOSTNAME("hostname"),
    OS_RELEASE("cat /etc/os-release"),
    KERNEL_NAME("uname -s"),
    KERNEL_VERSION("uname -r"),
    ARCHITECTURE("uname -m"),
    DOCKER_HELPER("sudo -n /usr/local/libexec/vpsgraph-docker-scan"),

    ;

    companion object {
        val hostDiscoveryCommands = listOf(HOSTNAME, OS_RELEASE, KERNEL_NAME, KERNEL_VERSION, ARCHITECTURE)
    }
}

data class CommandResult(
    val command: RemoteCommand,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val outputLimitExceeded: Boolean = false,
)

@Serializable
data class HostInfo(
    val hostname: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val kernelName: String? = null,
    val kernelVersion: String? = null,
    val architecture: String? = null,
)

enum class ScanErrorCode {
    INVALID_SSH_TARGET,
    SSH_CONNECTION_FAILED,
    SSH_HOST_UNREACHABLE,
    SSH_CONNECTION_REFUSED,
    SSH_AUTH_FAILED,
    SSH_HOST_KEY_UNKNOWN,
    SSH_HOST_KEY_MISMATCH,
    SSH_KEY_NOT_FOUND,
    SSH_KEY_UNREADABLE,
    SSH_KEY_INVALID,
    SSH_KEY_UNSUPPORTED,
    SSH_TIMEOUT,
    REMOTE_COMMAND_FAILED,
    HOST_DISCOVERY_PARTIAL,
}

data class ScanError(
    val code: ScanErrorCode,
    val userMessage: String,
    val cause: Throwable? = null,
) {
    companion object {
        fun invalidTarget(message: String) = ScanError(ScanErrorCode.INVALID_SSH_TARGET, message)
    }
}

sealed interface ScanResult<out T> {
    data class Success<T>(val value: T, val warning: ScanErrorCode? = null) : ScanResult<T>
    data class Failure(val error: ScanError) : ScanResult<Nothing>
}

interface SshExecutor : AutoCloseable {
    fun execute(
        target: SshTarget,
        commands: List<RemoteCommand>,
        onAuthenticated: () -> Unit = {},
    ): ScanResult<List<CommandResult>>

    override fun close() = Unit
}
