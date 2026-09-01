package com.ilkimgul.vpsgraph.core

class HostScanner(private val executor: SshExecutor) {
    fun scan(target: SshTarget, onAuthenticated: () -> Unit = {}): ScanResult<HostInfo> {
        val commandResults = when (val result = executor.execute(target, RemoteCommand.hostDiscoveryCommands, onAuthenticated)) {
            is ScanResult.Failure -> return result
            is ScanResult.Success -> result.value
        }
        return parseHost(commandResults)
    }

    fun scanWithDocker(target: SshTarget, onAuthenticated: () -> Unit = {}): ScanResult<HostDiscoveryResult> {
        val commandResults = when (val result = executor.execute(target, RemoteCommand.hostDiscoveryCommands + RemoteCommand.DOCKER_HELPER, onAuthenticated)) {
            is ScanResult.Failure -> return result
            is ScanResult.Success -> result.value
        }
        return when (val host = parseHost(commandResults)) {
            is ScanResult.Failure -> host
            is ScanResult.Success -> ScanResult.Success(
                HostDiscoveryResult(host.value, DockerDiscovery.from(commandResults.firstOrNull { it.command == RemoteCommand.DOCKER_HELPER })),
                host.warning,
            )
        }
    }

    private fun parseHost(results: List<CommandResult>): ScanResult<HostInfo> {
        val commandResults = results.associateBy { it.command }
        val hostnameCommand = commandResults[RemoteCommand.HOSTNAME]
        if (hostnameCommand?.timedOut == true) {
            return ScanResult.Failure(ScanError(ScanErrorCode.SSH_TIMEOUT, "Hostname discovery timed out."))
        }
        val hostname = hostnameCommand?.successfulOutput()
            ?: return ScanResult.Failure(
                ScanError(ScanErrorCode.REMOTE_COMMAND_FAILED, "The remote host name could not be retrieved."),
            )
        val osRelease = commandResults[RemoteCommand.OS_RELEASE]?.successfulOutput()?.let(OsReleaseParser::parse)
        val hostInfo = HostInfo(
            hostname = hostname,
            osName = osRelease?.name,
            osVersion = osRelease?.version,
            kernelName = commandResults[RemoteCommand.KERNEL_NAME]?.successfulOutput(),
            kernelVersion = commandResults[RemoteCommand.KERNEL_VERSION]?.successfulOutput(),
            architecture = commandResults[RemoteCommand.ARCHITECTURE]?.successfulOutput(),
        )
        val partial = RemoteCommand.hostDiscoveryCommands
            .filter { it != RemoteCommand.HOSTNAME }
            .any { commandResults[it]?.successfulOutput() == null }
        return ScanResult.Success(hostInfo, if (partial) ScanErrorCode.HOST_DISCOVERY_PARTIAL else null)
    }

    private fun CommandResult.successfulOutput(): String? =
        stdout.trim().takeIf { exitCode == 0 && !timedOut && !outputLimitExceeded && it.isNotEmpty() }
}

object HostGraphFactory {
    fun create(host: HostInfo, target: SshTarget): InfraGraph {
        val metadata = linkedMapOf(
            "hostname" to host.hostname,
            "ssh host" to target.host,
            "ssh username" to target.username,
        )
        host.osName?.let { metadata["operating system"] = it }
        host.osVersion?.let { metadata["os version"] = it }
        host.kernelName?.let { metadata["kernel"] = listOfNotNull(it, host.kernelVersion).joinToString(" ") }
        host.architecture?.let { metadata["architecture"] = it }

        return InfraGraph(nodes = listOf(InfraNode("server:scanned", host.hostname, NodeType.SERVER, metadata)), edges = emptyList())
    }
}
