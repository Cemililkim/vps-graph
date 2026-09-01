package com.ilkimgul.vpsgraph.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpsGraphCefNavigationPolicyTest {
    @Test
    fun `JBCef loadHTML synthetic document is allowed before first load`() {
        val policy = VpsGraphCefNavigationPolicy()
        assertFalse(policy.shouldCancelNavigation("file:///jbcefbrowser/123#url=about:blank", "about:blank"))
        assertFalse(policy.shouldCancelNavigation("file:///jbcefbrowser/123#url=about:blank", null))
        policy.markInitialLoadComplete()
        assertFalse(policy.shouldCancelNavigation("file:///jbcefbrowser/123#applications", "file:///jbcefbrowser/123#url=about:blank"))
        assertTrue(policy.shouldCancelNavigation("file:///jbcefbrowser/123#url=about:blank", "about:blank"))
    }

    @Test
    fun `external script and unrelated navigation is cancelled`() {
        val policy = VpsGraphCefNavigationPolicy().also { it.markInitialLoadComplete() }
        val bundled = "file:///jbcefbrowser/123#url=about:blank"
        assertTrue(policy.shouldCancelNavigation("https://reactflow.dev", bundled))
        assertTrue(policy.shouldCancelNavigation("javascript:alert(1)", bundled))
        assertTrue(policy.shouldCancelNavigation("data:text/html,not-the-app", bundled))
        assertTrue(policy.shouldCancelNavigation("file:///secret.txt", bundled))
        assertTrue(policy.shouldCancelNavigation(null, bundled))
    }

    @Test
    fun `untrusted URLs are never accepted as the initial app document`() {
        val policy = VpsGraphCefNavigationPolicy()
        assertTrue(policy.shouldCancelNavigation("https://example.test", "about:blank"))
        assertTrue(policy.shouldCancelNavigation("javascript:alert(1)", "about:blank"))
        assertTrue(policy.shouldCancelNavigation("data:text/html,not-the-app", "about:blank"))
    }
}
