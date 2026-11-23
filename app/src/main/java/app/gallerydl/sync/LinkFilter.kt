package app.gallerydl.sync

import java.net.URI

/**
 * Whether an address from another device may reach Home or Favorites. It still has to be a public web address: a device on
 * this network must not make the app fetch the network's own services (a router's page, a machine on the same Wi-Fi).
 * A name that only resolves to a private address (a wildcard DNS name, for one) is not caught here.
 */
internal fun isPublicWebLink(url: String): Boolean {
    val uri = try { URI(url) } catch (_: Exception) { return false }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    var host = uri.host?.lowercase() ?: return false
    if (host.startsWith("[")) return false                       // a bare IPv6 address, which may be this network's
    host = host.removeSuffix(".")
    if (host == "localhost" || !host.contains('.')) return false  // a machine on this network, named or bare
    val labels = host.split('.')
    // A last part made of numbers means the system reads the whole host as an address, and it has spellings of its own
    // ("127.1", "0x7f.0.0.1", "0177.0.0.1" are all this machine). Only the plain four-part form is understood here, so
    // every other spelling is refused.
    if (!isNumberLabel(labels.last())) return true
    if (labels.size != 4) return false
    val numbers = labels.map { label ->
        if (label.all { it in '0'..'9' } && (label.length == 1 || !label.startsWith("0"))) label.toIntOrNull() else null
    }
    if (numbers.any { it == null || it > 255 }) return false
    val a = numbers[0] ?: return false
    val b = numbers[1] ?: return false
    val local = a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
        (a == 192 && b == 168) || (a == 172 && b in 16..31)
    return !local
}

/** Whether a part of a host is a number the way an address is written: decimal digits, or 0x and hex digits. */
private fun isNumberLabel(label: String): Boolean {
    if (label.isEmpty()) return false
    if (label.all { it in '0'..'9' }) return true
    return label.startsWith("0x") && label.length > 2 && label.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
}
