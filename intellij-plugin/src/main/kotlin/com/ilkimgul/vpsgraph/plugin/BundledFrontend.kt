package com.ilkimgul.vpsgraph.plugin

private const val WEB_ROOT = "web/"
private val stylesheetTag = Regex("""<link rel=\"stylesheet\"[^>]*href=\"(\./assets/[^\"]+)\"[^>]*>""")
private val scriptTag = Regex("""<script type=\"module\"[^>]*src=\"(\./assets/[^\"]+)\"[^>]*></script>""")

object BundledFrontend {
    fun html(): String {
        val index = resourceText("index.html")
        val stylesheet = stylesheetTag.find(index)?.groupValues?.get(1)
            ?: error("VPS Graph frontend stylesheet is missing")
        val script = scriptTag.find(index)?.groupValues?.get(1)
            ?: error("VPS Graph frontend script is missing")

        val htmlWithStyles = stylesheetTag.replace(index) {
            "<style>${resourceText(stylesheet.removePrefix("./"))}</style>"
        }
        return scriptTag.replace(htmlWithStyles) {
            "<script type=\"module\">${resourceText(script.removePrefix("./"))}</script>"
        }
    }

    private fun resourceText(path: String): String = checkNotNull(javaClass.classLoader.getResource("$WEB_ROOT$path")) {
        "VPS Graph frontend resource is missing: $path"
    }.readText()
}
