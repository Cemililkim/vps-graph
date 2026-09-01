package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.ScanResult
import com.ilkimgul.vpsgraph.core.SshTarget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.awt.Rectangle
import javax.swing.JPanel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter

private const val MAX_BRIDGE_REQUEST_BYTES = 64 * 1024

internal fun parseBridgeRequest(request: String): JsonObject? {
    if (request.length > MAX_BRIDGE_REQUEST_BYTES || request.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_REQUEST_BYTES) return null
    return runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull()
}

class VpsGraphToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.setDefaultState(ToolWindowAnchor.RIGHT, ToolWindowType.DOCKED, Rectangle(0, 0, 960, 700))
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = if (!JBCefApp.isSupported()) {
            createUnavailablePanel()
        } else {
            runCatching { BundledFrontend.html() }
                .fold(
                    onSuccess = { createBrowserPanel(project, toolWindow, it) },
                    onFailure = { createUnavailablePanel("VPS Graph bundled frontend could not be loaded. Reinstall or update the plugin.") },
                )
        }
        toolWindow.component.add(
            content,
            BorderLayout.CENTER,
        )
    }

    private fun createBrowserPanel(project: Project, toolWindow: ToolWindow, frontendHtml: String): SimpleToolWindowPanel {
        val browser = JBCefBrowser()
        val graphQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        val changes = ChangesOrchestrator.local()
        val coordinator = HostScanCoordinator(browser, changes)
        val connectionPreferences = ApplicationManager.getApplication().getService(RememberedConnectionService::class.java)
        graphQuery.addHandler { request ->
            val payload = parseBridgeRequest(request)
                ?: return@addHandler JBCefJSQuery.Response("Invalid VPS Graph bridge request", 400, "Bad request")
            when ((payload["type"] as? JsonPrimitive)?.content) {
                "GET_GRAPH" -> JBCefJSQuery.Response(coordinator.graphJson())
                "GET_CONNECTION_PREFERENCES" -> JBCefJSQuery.Response(connectionPreferencesJson(connectionPreferences))
                "GET_CHANGES" -> JBCefJSQuery.Response(Json.encodeToString(changes.current()))
                "COMPARE_SNAPSHOTS" -> compareSnapshotsResponse(payload, changes)
                "SCAN_HOST" -> scanResponse(payload, coordinator, connectionPreferences)
                "PICK_PRIVATE_KEY" -> JBCefJSQuery.Response(Json.encodeToString(choosePrivateKey(project).orEmpty()))
                else -> JBCefJSQuery.Response("Unsupported VPS Graph bridge request", 400, "Bad request")
            }
        }

        val navigationPolicy = VpsGraphCefNavigationPolicy()
        browser.setOpenLinksInExternalBrowser(false)
        browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, targetFrameName: String): Boolean {
                // No unmanaged browser windows are valid for the embedded app.
                return true
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser,
                frame: CefFrame,
                request: org.cef.network.CefRequest,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean {
                if (!frame.isMain) return false
                return navigationPolicy.shouldCancelNavigation(request.url, cefBrowser.url)
            }

            override fun onOpenURLFromTab(
                cefBrowser: CefBrowser,
                frame: CefFrame,
                targetUrl: String,
                userGesture: Boolean,
            ): Boolean = true
        }, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    if (navigationPolicy.isBundledDocument(cefBrowser.url)) {
                        navigationPolicy.markInitialLoadComplete()
                        cefBrowser.executeJavaScript(graphBridge(graphQuery), cefBrowser.url, 0)
                    }
                }
            }
        }, browser.cefBrowser)
        browser.loadHTML(frontendHtml)

        return SimpleToolWindowPanel(true, true).also { panel ->
            panel.setContent(browser.component)
            Disposer.register(toolWindow.disposable, browser)
            Disposer.register(toolWindow.disposable, graphQuery)
            Disposer.register(toolWindow.disposable, coordinator)
        }
    }

    private fun createUnavailablePanel(message: String = "VPS Graph needs the JetBrains Chromium Embedded Framework (JCEF) in this IDE runtime."): JPanel = JPanel(BorderLayout()).apply {
        add(
            JBLabel(message),
            BorderLayout.NORTH,
        )
    }

    private fun scanResponse(
        payload: kotlinx.serialization.json.JsonObject,
        coordinator: HostScanCoordinator,
        connectionPreferences: RememberedConnectionService,
    ): JBCefJSQuery.Response {
        val target = SshTarget.create(
            host = (payload["host"] as? JsonPrimitive)?.content.orEmpty(),
            port = (payload["port"] as? JsonPrimitive)?.intOrNull ?: -1,
            username = (payload["username"] as? JsonPrimitive)?.content.orEmpty(),
            privateKeyPath = (payload["privateKeyPath"] as? JsonPrimitive)?.content.orEmpty(),
        )
        return when (target) {
            is ScanResult.Failure -> JBCefJSQuery.Response(
                buildJsonObject {
                    put("accepted", false)
                    put("errorCode", target.error.code.name)
                    put("errorMessage", target.error.userMessage)
                }.toString(),
            )
            is ScanResult.Success -> {
                val accepted = coordinator.start(target.value)
                if (accepted) connectionPreferences.update(
                    target.value,
                    (payload["rememberConnection"] as? JsonPrimitive)?.booleanOrNull == true,
                )
                JBCefJSQuery.Response(buildJsonObject {
                    put("accepted", accepted)
                    if (!accepted) put("errorMessage", "A scan is already in progress.")
                }.toString())
            }
        }
    }

    private fun connectionPreferencesJson(preferences: RememberedConnectionService): String {
        val remembered = preferences.remembered()
        return buildJsonObject {
            put("remembered", remembered != null)
            remembered?.let {
                put("host", it.host)
                put("port", it.port)
                put("username", it.username)
                put("privateKeyPath", it.privateKeyPath)
                put("privateKeyExists", it.privateKeyExists)
            }
        }.toString()
    }

    private fun compareSnapshotsResponse(
        payload: kotlinx.serialization.json.JsonObject,
        changes: ChangesOrchestrator,
    ): JBCefJSQuery.Response {
        val previous = (payload["previousSnapshotId"] as? JsonPrimitive)?.content.orEmpty()
        val current = (payload["currentSnapshotId"] as? JsonPrimitive)?.content.orEmpty()
        return JBCefJSQuery.Response(Json.encodeToString(changes.compare(previous, current)))
    }

    private fun choosePrivateKey(project: Project): String? {
        var path: String? = null
        val select = {
            path = FileChooser.chooseFile(
                FileChooserDescriptor(true, false, false, false, false, false).withTitle("Select SSH private key"),
                project,
                null,
            )?.path
        }
        if (ApplicationManager.getApplication().isDispatchThread) select() else ApplicationManager.getApplication().invokeAndWait(select)
        return path
    }

    private fun graphBridge(query: JBCefJSQuery): String = """
        window.vpsGraph = {
          requestGraph: function () {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'GET_GRAPH' })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          },
          requestConnectionPreferences: function () {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'GET_CONNECTION_PREFERENCES' })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          },
          requestChanges: function () {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'GET_CHANGES' })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          },
          compareSnapshots: function (previousSnapshotId, currentSnapshotId) {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'COMPARE_SNAPSHOTS', previousSnapshotId: previousSnapshotId, currentSnapshotId: currentSnapshotId })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          },
          scanHost: function (target) {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'SCAN_HOST', host: target.host, port: target.port, username: target.username, privateKeyPath: target.privateKeyPath, rememberConnection: target.rememberConnection === true })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          },
          choosePrivateKey: function () {
            return new Promise(function (resolve, reject) {
              ${query.inject("JSON.stringify({ type: 'PICK_PRIVATE_KEY' })", "function(response) { resolve(response); }", "function(code, message) { reject(new Error(message)); }")}
            });
          }
        };
        window.dispatchEvent(new Event('vps-graph-bridge-ready'));
    """.trimIndent()
}
