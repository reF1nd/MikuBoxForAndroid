package io.nekohasekai.sagernet.fmt.wireguard

import android.util.Base64
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.ini4j.Ini
import org.ini4j.Profile
import java.io.StringReader
import java.net.URI

fun parseReserved(value: String): List<Int>? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val values = trimmed.removePrefix("[").removeSuffix("]").listByLineOrComma()
    if (values.size == 3) {
        val bytes = values.map { it.trim().toIntOrNull() }
        if (bytes.all { it != null && it in 0..255 }) return bytes.filterNotNull()
    }
    return try {
        Base64.decode(trimmed, Base64.DEFAULT)
            .takeIf { it.size == 3 }
            ?.map { it.toInt() and 0xFF }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun requireKey(value: String, name: String, optional: Boolean = false) {
    if (optional && value.isBlank()) return
    val decoded = try {
        Base64.decode(value.trim(), Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }
    require(decoded?.size == 32) { "$name must be a base64-encoded 32-byte key" }
}

private fun requirePrefixes(value: String, name: String): List<String> {
    val prefixes = value.listByLineOrComma()
    require(prefixes.isNotEmpty()) { "$name must not be empty" }
    prefixes.forEach { prefix ->
        val address = prefix.substringBeforeLast('/', "")
        val bits = prefix.substringAfterLast('/', "").toIntOrNull()
        val maxBits = when {
            !address.isIpAddress() -> -1
            address.contains(':') -> 128
            else -> 32
        }
        require(bits != null && bits in 0..maxBits) { "Invalid $name prefix: $prefix" }
    }
    return prefixes
}

private fun parseEndpoint(value: String): Pair<String, Int> {
    val endpoint = value.trim()
    val separator = if (endpoint.startsWith("[")) endpoint.lastIndexOf("]:") + 1 else endpoint.lastIndexOf(':')
    require(separator > 0) { "Invalid WireGuard peer endpoint: $value" }
    val address = endpoint.substring(0, separator).unwrapIPV6Host()
    val port = endpoint.substring(separator + 1).toIntOrNull()
    require(address.isNotBlank() && port != null && port in 1..65535) {
        "Invalid WireGuard peer endpoint: $value"
    }
    return address to port
}

private fun parsePeer(section: Profile.Section): WireGuardBean.Peer {
    val (address, port) = parseEndpoint(section["Endpoint"] ?: error("Missing WireGuard peer endpoint"))
    return WireGuardBean.Peer().apply {
        this.address = address
        this.port = port
        publicKey = section["PublicKey"] ?: error("Missing WireGuard peer public key")
        preSharedKey = section["PresharedKey"].orEmpty()
        allowedIPs = section.getAll("AllowedIPs")
            ?.flatMap { it.split(',') }
            ?.joinToString("\n") { it.trim() }
            ?.takeIf { it.isNotBlank() }
            ?: error("Missing WireGuard peer AllowedIPs")
        persistentKeepaliveInterval = section["PersistentKeepalive"]?.let {
            it.toIntOrNull() ?: error("Invalid WireGuard PersistentKeepalive: $it")
        } ?: 0
        reserved = section["Reserved"].orEmpty()
    }
}

fun parseWireGuardPeers(config: String): List<WireGuardBean.Peer> {
    val sections = Ini(StringReader(config)).getAll("Peer")
    require(!sections.isNullOrEmpty()) { "Missing WireGuard peer sections" }
    return sections.map(::parsePeer)
}

fun formatWireGuardPeers(peers: List<WireGuardBean.Peer>): String = peers.joinToString("\n\n") { peer ->
    buildString {
        appendLine("[Peer]")
        appendLine("PublicKey = ${peer.publicKey}")
        if (peer.preSharedKey.isNotBlank()) appendLine("PresharedKey = ${peer.preSharedKey}")
        appendLine("AllowedIPs = ${peer.allowedIPs.listByLineOrComma().joinToString(", ")}")
        appendLine("Endpoint = ${peer.address.wrapIPV6Host()}:${peer.port}")
        if (peer.persistentKeepaliveInterval > 0) {
            appendLine("PersistentKeepalive = ${peer.persistentKeepaliveInterval}")
        }
        if (peer.reserved.isNotBlank()) append("Reserved = ${peer.reserved}")
    }.trimEnd()
}

fun parseWireGuardConfig(config: String): WireGuardBean {
    val ini = Ini(StringReader(config))
    val iface = ini["Interface"] ?: error("Missing WireGuard Interface section")
    val addresses = iface.getAll("Address")
        ?.flatMap { it.split(',') }
        ?.joinToString("\n") { it.trim() }
        ?.takeIf { it.isNotBlank() }
        ?: error("Missing WireGuard interface address")
    val parsedPeers = ini.getAll("Peer")?.map(::parsePeer)
    require(!parsedPeers.isNullOrEmpty()) { "Missing WireGuard peer sections" }
    val metadataDns = wireGuardMetadata(config, "DNS")?.takeIf { it.isNotBlank() }
    val dnsEntries = if (metadataDns != null) {
        listOf(metadataDns)
    } else {
        iface.getAll("DNS")?.flatMap { it.split(',') }?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
    require(dnsEntries.size <= 1) { "MikuBox supports only one WireGuard DNS server" }

    return WireGuardBean().apply {
        initializeDefaultValues()
        localAddress = addresses
        privateKey = iface["PrivateKey"].orEmpty()
        mtu = iface["MTU"]?.toIntOrNull() ?: mtu
        listenPort = iface["ListenPort"]?.toIntOrNull() ?: 0
        dnsServer = dnsEntries.singleOrNull()?.let(::wgQuickDnsToUri).orEmpty()
        peers = ArrayList(parsedPeers)
        usePrimaryPeer(parsedPeers.first())
    }
}

private fun wgQuickDnsToUri(value: String): String {
    if (value.contains("://")) return value
    val address = value.unwrapIPV6Host()
    require(address.isIpAddress()) { "WireGuard DNS must be an IP address or DNS URI" }
    return "udp://${address.wrapIPV6Host()}"
}

private fun wireGuardMetadata(config: String, key: String): String? {
    val prefix = "# MikuBox-$key ="
    return config.lineSequence()
        .firstOrNull { it.trimStart().startsWith(prefix) }
        ?.substringAfter(prefix)
        ?.trim()
}

fun WireGuardBean.toWgQuickConfig(): String {
    syncPrimaryPeer()
    return buildString {
        appendLine("[Interface]")
        appendLine("Address = ${localAddress.listByLineOrComma().joinToString(", ")}")
        appendLine("PrivateKey = $privateKey")
        if (listenPort > 0) appendLine("ListenPort = $listenPort")
        if (mtu > 0) appendLine("MTU = $mtu")
        if (dnsServer.isNotBlank()) {
            val uri = URI(dnsServer)
            val standardDns = uri.host?.unwrapIPV6Host()?.takeIf {
                uri.scheme.equals("udp", ignoreCase = true) &&
                    (uri.port == -1 || uri.port == 53) &&
                    it.isIpAddress()
            }
            if (standardDns != null) {
                appendLine("DNS = $standardDns")
            } else {
                appendLine("# MikuBox-DNS = $dnsServer")
            }
        }
        appendLine()
        append(formatWireGuardPeers(peers))
        appendLine()
    }
}

private fun WireGuardBean.Peer.toSingBoxPeer(): SingBoxOptions.WireGuardPeer {
    require(address.isNotBlank()) { "WireGuard peer address must not be empty" }
    require(port in 1..65535) { "WireGuard peer port must be between 1 and 65535" }
    requireKey(publicKey, "WireGuard peer public key")
    requireKey(preSharedKey, "WireGuard peer pre-shared key", optional = true)
    require(persistentKeepaliveInterval in 0..65535) {
        "WireGuard persistent keepalive must be between 0 and 65535"
    }
    return SingBoxOptions.WireGuardPeer().apply {
        address = this@toSingBoxPeer.address
        port = this@toSingBoxPeer.port
        public_key = publicKey
        pre_shared_key = preSharedKey.takeIf { it.isNotBlank() }
        allowed_ips = requirePrefixes(allowedIPs, "AllowedIPs")
        persistent_keepalive_interval = persistentKeepaliveInterval.takeIf { it > 0 }
        if (this@toSingBoxPeer.reserved.isNotBlank()) {
            reserved = requireNotNull(parseReserved(this@toSingBoxPeer.reserved)) {
                "WireGuard reserved must contain exactly three bytes (0-255)"
            }
        }
    }
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    bean.syncPrimaryPeer()
    requireKey(bean.privateKey, "WireGuard private key")
    require(bean.listenPort in 0..65535) { "WireGuard listen port must be between 0 and 65535" }
    require(bean.mtu > 0) { "WireGuard MTU must be greater than zero" }
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = requirePrefixes(bean.localAddress, "WireGuard interface address")
        private_key = bean.privateKey
        listen_port = bean.listenPort.takeIf { it > 0 }
        mtu = bean.mtu
        peers = bean.peers.map { it.toSingBoxPeer() }
    }
}
