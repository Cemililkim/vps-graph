package com.ilkimgul.vpsgraph.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HostTargetResolverTest {
    private val dial = CaddyDial("host.docker.internal", 19999, CaddyDialKind.HOST_TARGET)
    private val service = SystemdServiceInfo("netdata.service")

    @Test fun `resolves one non-loopback TCP owner to its canonical service`() {
        val result = HostTargetResolver.resolve(dial, systemd(service), listeners(listener("172.17.0.1", service.unit)))
        assertEquals(HostResolutionState.HOST_SERVICE, result.state)
        assertEquals(service.unit, result.service?.unit)
    }

    @Test fun `allows loopback plus multiple eligible listeners owned by the same service`() {
        val result = HostTargetResolver.resolve(dial, systemd(service), listeners(
            listener("127.0.0.1", service.unit, loopback = true),
            listener("0.0.0.0", service.unit),
            listener("172.17.0.1", service.unit),
        ))
        assertEquals(HostResolutionState.HOST_SERVICE, result.state)
        assertEquals(2, result.listeners.size)
        assertEquals(service.unit, result.service?.unit)
    }

    @Test fun `ignores loopback and UDP listeners`() {
        val result = HostTargetResolver.resolve(dial, systemd(service), listeners(listener("127.0.0.1", service.unit, loopback = true), listener("0.0.0.0", service.unit, protocol = "udp")))
        assertEquals(HostResolutionState.HOST_TARGET, result.state)
    }

    @Test fun `keeps incomplete listener discovery conservative`() {
        val result = HostTargetResolver.resolve(dial, systemd(service), listeners(listener("172.17.0.1", service.unit), state = OptionalDiscoveryState.PARTIAL))
        assertEquals(HostResolutionState.HOST_PARTIAL, result.state)
    }

    @Test fun `does not fabricate missing services and marks mixed ownership ambiguous`() {
        assertEquals(HostResolutionState.HOST_LISTENER, HostTargetResolver.resolve(dial, systemd(), listeners(listener("172.17.0.1", "missing.service"))).state)
        val mixed = listeners(listener("172.17.0.1", service.unit), listener("0.0.0.0", null, ownership = ListenerOwnershipState.UNRESOLVED))
        assertEquals(HostResolutionState.HOST_AMBIGUOUS, HostTargetResolver.resolve(dial, systemd(service), mixed).state)
    }


    @Test fun `conflicting canonical service owners are ambiguous`() {
        val other = SystemdServiceInfo("other.service")
        val result = HostTargetResolver.resolve(dial, systemd(service, other), listeners(
            listener("10.0.0.1", service.unit),
            listener("10.0.0.2", other.unit),
        ))
        assertEquals(HostResolutionState.HOST_AMBIGUOUS, result.state)
        assertEquals(listOf("netdata.service", "other.service"), result.services.map { it.unit }.sorted())
    }

    @Test fun `eligible listener without deterministic ownership stays listener-level`() {
        val result = HostTargetResolver.resolve(dial, systemd(service), listeners(listener("0.0.0.0", null, ownership = ListenerOwnershipState.UNRESOLVED)))
        assertEquals(HostResolutionState.HOST_LISTENER, result.state)
        assertEquals(1, result.listeners.size)
    }

    @Test fun `preserves legacy host target when optional discovery is absent`() {
        assertEquals(HostResolutionState.HOST_TARGET, HostTargetResolver.resolve(dial, SystemdDiscoveryInfo(), ListenerDiscoveryInfo()).state)
    }

    private fun systemd(vararg services: SystemdServiceInfo) = SystemdDiscoveryInfo(OptionalDiscoveryState.DISCOVERED, services = services.toList())
    private fun listeners(vararg items: HostListenerInfo, state: OptionalDiscoveryState = OptionalDiscoveryState.DISCOVERED) = ListenerDiscoveryInfo(state, items = items.toList())
    private fun listener(address: String, unit: String?, loopback: Boolean = false, protocol: String = "tcp", ownership: ListenerOwnershipState = ListenerOwnershipState.SYSTEMD_SERVICE) =
        HostListenerInfo(protocol, address, 19999, address == "0.0.0.0", loopback, ownership, unit)
}
