package com.filip.camfirestick

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NetUtils {
    fun localIpv4(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    /** Procura na rede /24 do Fire Stick aparelhos com a porta RTSP aberta. */
    fun scanRtsp(port: Int = 554): List<String> {
        val ip = localIpv4() ?: return emptyList()
        val prefix = ip.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(64)
        try {
            val futures = (1..254).map { n ->
                val host = "$prefix.$n"
                pool.submit<String?> {
                    try {
                        Socket().use { it.connect(InetSocketAddress(host, port), 600) }
                        host
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
