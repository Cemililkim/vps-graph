package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.SshTarget
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Files
import java.nio.file.Path

data class RememberedConnection(
    val host: String,
    val port: Int,
    val username: String,
    val privateKeyPath: String,
    val privateKeyExists: Boolean,
)

@Service(Service.Level.APP)
@State(name = "VpsGraphRememberedConnection", storages = [Storage("vpsGraphConnection.xml")])
class RememberedConnectionService : PersistentStateComponent<RememberedConnectionService.ConnectionState> {
    private var connectionState = ConnectionState()

    override fun getState(): ConnectionState = connectionState

    override fun loadState(state: ConnectionState) {
        connectionState = state.copy()
    }

    fun update(target: SshTarget, remember: Boolean) {
        connectionState = if (remember) {
            ConnectionState(true, target.host, target.port, target.username, target.privateKeyPath)
        } else {
            ConnectionState()
        }
    }

    fun remembered(): RememberedConnection? {
        val state = connectionState
        if (!state.remembered || state.host.isBlank() || state.port !in 1..65535 || state.username.isBlank() || state.privateKeyPath.isBlank()) return null
        return RememberedConnection(
            state.host,
            state.port,
            state.username,
            state.privateKeyPath,
            runCatching { Files.isRegularFile(Path.of(state.privateKeyPath)) }.getOrDefault(false),
        )
    }

    data class ConnectionState(
        var remembered: Boolean = false,
        var host: String = "",
        var port: Int = 22,
        var username: String = "",
        var privateKeyPath: String = "",
    )
}
