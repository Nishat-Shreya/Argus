package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validation and normalization of IP literals (plan §3.3/§6.3). Literals only — T24 pins that
 * no {@code InetAddress} lookup ever happens.
 */
class IpAddressTest {

    @Test
    void acceptsDottedQuadIpv4() {
        for (String ip : List.of("0.0.0.0", "1.2.3.4", "255.255.255.255", "192.168.1.1")) {
            assertEquals(ip, IpAddress.normalize(ip));
        }
    }

    @Test
    void trimsWhitespace() {
        assertEquals("8.8.8.8", IpAddress.normalize("  8.8.8.8 "));
    }

    @Test
    void rejectsLeadingZeroOctets() {
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("010.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("1.2.3.04"));
    }

    @Test
    void rejectsOutOfRangeAndMalformedIpv4() {
        for (String bad : List.of("256.0.0.1", "1.2.3", "1.2.3.4.5", "1.2.3.", ".1.2.3", "1..2.3")) {
            assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize(bad),
                    "expected rejection for: " + bad);
        }
    }

    @Test
    void acceptsFullAndCompressedIpv6() {
        assertEquals("2001:0db8:0000:0000:0000:ff00:0042:8329",
                IpAddress.normalize("2001:0db8:0000:0000:0000:ff00:0042:8329"));
        assertEquals("2001:db8::ff00:42:8329", IpAddress.normalize("2001:db8::ff00:42:8329"));
        assertEquals("::1", IpAddress.normalize("::1"));
        assertEquals("::", IpAddress.normalize("::"));
        assertEquals("fe80::1", IpAddress.normalize("FE80::1"));
    }

    @Test
    void rejectsDoubleColonTwiceAndOverlongIpv6() {
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("1::2::3"));
        assertThrows(IllegalArgumentException.class,
                () -> IpAddress.normalize("1:2:3:4:5:6:7:8:9"));
        assertThrows(IllegalArgumentException.class,
                () -> IpAddress.normalize("12345::1"));
    }

    @Test
    void rejectsZoneIdBracketsAndMappedTail() {
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("fe80::1%eth0"));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("[::1]"));
        assertThrows(IllegalArgumentException.class,
                () -> IpAddress.normalize("::ffff:1.2.3.4"));
    }

    @Test
    void rejectsHostnamesAndCidrAndPorts() {
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("example.com"));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("1.2.3.4/24"));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize("1.2.3.4:80"));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> IpAddress.normalize(null));
    }

    @Test
    void isIpv4DistinguishesFamilies() {
        assertTrue(IpAddress.isIpv4("1.2.3.4"));
        assertFalse(IpAddress.isIpv4("::1"));
    }

    @Test
    void doesNotResolveNames() {
        assertFalse(IpAddress.isValid("localhost"));
        assertFalse(IpAddress.isValid("dns.google"));
    }
}
