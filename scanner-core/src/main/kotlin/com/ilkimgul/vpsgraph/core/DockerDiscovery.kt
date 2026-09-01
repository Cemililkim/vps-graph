package com.ilkimgul.vpsgraph.core

object DockerDiscovery {
    fun from(command: CommandResult?): DockerDiscoveryResult {
        if (command == null) return DockerDiscoveryResult(DockerDiscoveryState.SCAN_FAILED)
        if (command.timedOut || command.outputLimitExceeded) return DockerDiscoveryResult(DockerDiscoveryState.SCAN_FAILED)
        if (command.exitCode == 0) return DockerHelperParser.parse(command.stdout)
        val error = command.stderr.lowercase()
        return when {
            "not found" in error || "no such file" in error -> DockerDiscoveryResult(DockerDiscoveryState.HELPER_NOT_INSTALLED)
            "not allowed" in error || "not in the sudoers" in error || "password" in error || "permission denied" in error || "not executable" in error -> DockerDiscoveryResult(DockerDiscoveryState.HELPER_NOT_AUTHORIZED)
            else -> DockerDiscoveryResult(DockerDiscoveryState.SCAN_FAILED)
        }
    }
}
