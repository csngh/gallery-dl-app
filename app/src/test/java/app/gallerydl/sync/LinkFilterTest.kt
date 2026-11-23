package app.gallerydl.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which addresses from another device may reach Home: public web addresses only, in whatever way they are written. */
class LinkFilterTest {
    private fun accepted(vararg urls: String) = urls.forEach { assertTrue("should be accepted: $it", isPublicWebLink(it)) }
    private fun refused(vararg urls: String) = urls.forEach { assertFalse("should be refused: $it", isPublicWebLink(it)) }

    @Test fun publicNamesAndAddressesPass() = accepted(
        "https://example.com/a.jpg",
        "http://sub.example.org/x?y=1",
        "https://example.com./a",            // a trailing dot is still the same public name
        "http://1.2.3.example.com/",          // numbers in front of a name are only a name
        "https://8.8.8.8/x",
        "http://172.15.0.1/",                 // just outside the private 172.16 - 172.31 block
        "http://172.32.0.1/",
    )

    @Test fun thisNetworksOwnAddressesAreRefused() = refused(
        "http://192.168.0.1/",
        "http://10.0.0.5/admin",
        "http://127.0.0.1/",
        "http://172.16.0.1/",
        "http://172.31.255.255/",
        "http://169.254.169.254/",
        "http://0.0.0.0/",
        "http://224.0.0.1/",
        "http://localhost/",
        "http://localhost./",
        "http://router/",
        "http://[::1]/",
    )

    /** The system reads these as numbers in its own ways, and they are all this machine. */
    @Test fun otherSpellingsOfAnAddressAreRefused() = refused(
        "http://127.1/",
        "http://0x7f.0.0.1/",
        "http://0177.0.0.1/",
        "http://0300.0250.0.1/",
        "http://2130706433/",
        "http://127.0.0.1./",
        "http://1.2.3/",
        "http://999.1.1.1/",
    )

    @Test fun otherSchemesAndJunkAreRefused() = refused(
        "ftp://example.com/a",
        "file:///etc/passwd",
        "javascript:alert(1)",
        "not a url",
        "",
    )
}
