package com.ilkimgul.vpsgraph.plugin

import com.ilkimgul.vpsgraph.core.SshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RememberedConnectionServiceTest {
    private val target = SshTarget("vps.example", 22, "vpsgraph", "/missing/id_ed25519")

    @Test fun `unchecked connection persists nothing`() {
        val service = RememberedConnectionService()
        service.update(target, false)

        assertNull(service.remembered())
        assertFalse(service.state.remembered)
        assertEquals("", service.state.privateKeyPath)
    }

    @Test fun `checked connection restores after service recreation and key path updates`() {
        val service = RememberedConnectionService()
        service.update(target, true)
        val recreated = RememberedConnectionService().also { it.loadState(service.state) }

        assertEquals(target.host, recreated.remembered()?.host)
        assertEquals(target.privateKeyPath, recreated.remembered()?.privateKeyPath)
        recreated.update(target.copy(privateKeyPath = "/missing/replacement"), true)
        assertEquals("/missing/replacement", recreated.remembered()?.privateKeyPath)
    }

    @Test fun `unchecking removes only remembered form data and missing key never guesses fallback`() {
        val service = RememberedConnectionService()
        service.update(target, true)
        val remembered = assertNotNull(service.remembered())
        assertEquals(target.privateKeyPath, remembered.privateKeyPath)
        assertFalse(remembered.privateKeyExists)

        service.update(target, false)
        assertNull(service.remembered())
    }

    @Test fun `persistent state has no secret-bearing fields`() {
        val fields = RememberedConnectionService.ConnectionState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(setOf("remembered", "host", "port", "username", "privateKeyPath").all { it in fields })
        assertTrue(setOf("privateKeyContents", "passphrase", "password", "decryptedKey", "credentials").none { it in fields })
    }
}
