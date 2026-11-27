package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.lan.LanLink
import com.wirelessalien.zipxtract.lan.LanSubnet
import com.wirelessalien.zipxtract.lan.ipv4
import com.wirelessalien.zipxtract.lan.matchingLanRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

class LanRoutingTest {
    private val wifi = LanSubnet(requireNotNull(ipv4("192.168.0.8")), 24, null, LanLink.WIFI, "wlan0")
    private val wired = LanSubnet(requireNotNull(ipv4("192.168.0.17")), 24, null, LanLink.WIRED, "rndis0")
    private val remote = requireNotNull(ipv4("192.168.0.30"))

    @Test fun wiredAdvertisementChoosesCableWhenSubnetsOverlap() {
        assertEquals(listOf(wired), matchingLanRoutes(listOf(wifi, wired), remote, null, LanLink.WIRED))
    }

    @Test fun legacyAdvertisementKeepsWifiButWorksOnWiredOnlyDevice() {
        assertEquals(listOf(wifi), matchingLanRoutes(listOf(wired, wifi), remote, null, null))
        assertEquals(listOf(wired), matchingLanRoutes(listOf(wired), remote, null, null))
    }

    @Test fun rejectsSelfAndAddressesOutsideBoundSubnet() {
        assertEquals(emptyList<LanSubnet>(), matchingLanRoutes(listOf(wired), wired.address, null, LanLink.WIRED))
        assertEquals(emptyList<LanSubnet>(), matchingLanRoutes(listOf(wired),
            requireNotNull(ipv4("192.168.1.20")), null, LanLink.WIRED))
    }
}
