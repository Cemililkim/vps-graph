package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.HostDockerGraphFactory
import com.ilkimgul.vpsgraph.core.HostScanner
import com.ilkimgul.vpsgraph.core.InfraGraph
import com.ilkimgul.vpsgraph.core.ScanResult
import com.ilkimgul.vpsgraph.core.SshTarget
import com.ilkimgul.vpsgraph.core.SshjExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Coordinates one cancellable, read-only host scan. Remote commands live only in scanner-core. */
class HostScanCoordinator(
    private val browser: JBCefBrowser,
    private val changes: ChangesOrchestrator = ChangesOrchestrator.local(),
) : Disposable {
    private val log = Logger.getInstance(HostScanCoordinator::class.java)
    private val graph = AtomicReference(MockInfraGraph.value)
    @Volatile private var activeScan: Future<*>? = null
    @Volatile private var activeExecutor: SshjExecutor? = null

    fun graphJson(): String = Json.encodeToString(graph.get())

    fun start(target: SshTarget): Boolean = synchronized(this) {
        if (activeScan?.isDone == false) return false
        activeScan = AppExecutorUtil.getAppExecutorService().submit { scan(target) }
        true
    }

    private fun scan(target: SshTarget) {
        log.info("Starting host scan")
        emitStatus("CONNECTING")
        try {
            SshjExecutor().use { executor ->
                activeExecutor = executor
                when (val result = HostScanner(executor).scanWithDocker(target) { emitStatus("SCANNING") }) {
                    is ScanResult.Success -> {
                        val scannedGraph = HostDockerGraphFactory.create(result.value, target)
                        graph.set(scannedGraph)
                        log.info("Host scan completed")
                        val changesResponse = runCatching { changes.capture(target, scannedGraph) }
                            .getOrElse {
                                log.info("Snapshot comparison failed: category=CAPTURE_OR_COMPARE schema=1")
                                ChangesResponse.failure("HISTORY_UNAVAILABLE", "The scan completed, but local change history could not be updated.")
                            }
                        emitStatus("CONNECTED", scannedGraph, changesResponse = changesResponse)
                    }
                    is ScanResult.Failure -> {
                        log.info("Host discovery failed: ${result.error.code}")
                        emitStatus("ERROR", errorCode = result.error.code.name, errorMessage = result.error.userMessage)
                    }
                }
            }
        } catch (_: Exception) {
            log.info("Host discovery failed: REMOTE_COMMAND_FAILED")
            emitStatus("ERROR", errorCode = "REMOTE_COMMAND_FAILED", errorMessage = "Host discovery could not be completed.")
        } finally {
            activeExecutor = null
        }
    }

    private fun emitStatus(
        state: String,
        graph: InfraGraph? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        changesResponse: ChangesResponse? = null,
    ) {
        val event = buildJsonObject {
            put("state", state)
            graph?.let { put("graph", Json.encodeToJsonElement(InfraGraph.serializer(), it)) }
            changesResponse?.let { put("changes", Json.encodeToJsonElement(ChangesResponse.serializer(), it)) }
            errorCode?.let { put("errorCode", it) }
            errorMessage?.let { put("errorMessage", it) }
        }.toString()
        val safeJsonLiteral = Json.encodeToString(event)
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "window.dispatchEvent(new CustomEvent('vps-graph-scan-status', { detail: JSON.parse($safeJsonLiteral) }));",
                browser.cefBrowser.url,
                0,
            )
        }
    }

    override fun dispose() {
        activeScan?.cancel(true)
        activeExecutor?.close()
    }
}
