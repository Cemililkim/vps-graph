package com.ilkimgul.vpsgraph.plugin

/**
 * Guards the one synthetic file URL that JBCefBrowserBase.loadHTML creates.
 * Once that document has loaded, only same-document (fragment) navigation is
 * allowed. HTTP(S), javascript, data, and unrelated file URLs are rejected.
 */
internal class VpsGraphCefNavigationPolicy {
    private var initialLoadPending = true

    fun shouldCancelNavigation(targetUrl: String?, currentUrl: String?): Boolean {
        val target = targetUrl?.trim().orEmpty()
        val current = currentUrl?.trim().orEmpty()
        if (target.isEmpty()) return true
        if (target.substringBefore(':').lowercase() in BLOCKED_SCHEMES) return true
        if (initialLoadPending && isBundledLoadUrl(target, current)) return false
        if (current.isEmpty()) return true

        val targetDocument = target.substringBefore('#')
        val currentDocument = current.substringBefore('#')
        return targetDocument != currentDocument
    }

    fun markInitialLoadComplete() {
        initialLoadPending = false
    }

    fun isBundledDocument(url: String?): Boolean =
        url?.trim()?.startsWith(JCEF_LOAD_HTML_PREFIX, ignoreCase = true) == true

    private companion object {
        val BLOCKED_SCHEMES = setOf("http", "https", "javascript", "data")
        const val JCEF_LOAD_HTML_PREFIX = "file:///jbcefbrowser/"

        fun isBundledLoadUrl(target: String, current: String): Boolean =
            (current.isEmpty() || current.equals("about:blank", ignoreCase = true)) &&
                target.startsWith(JCEF_LOAD_HTML_PREFIX, ignoreCase = true) &&
                target.substringAfter("#url=", missingDelimiterValue = "")
                    .equals("about:blank", ignoreCase = true)
    }
}
