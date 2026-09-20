package io.github.oleglog.olcrtc.client.vpn

import android.os.ParcelFileDescriptor
import io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile
import mobilecore.Mobilecore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class OpenFluxSession(
    private val profile: OpenFluxProfile,
    private val dnsServer: String,
    private val establishTun: () -> ParcelFileDescriptor,
    private val isPacketAllowed: ((packet: ByteArray) -> Boolean)? = null,
    private val onFail: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : VpnTunnelSession {

    private val closed = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val outputLock = Any()
    private val bytesUp = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesDown = java.util.concurrent.atomic.AtomicLong(0)

    private var tunnelPfd: ParcelFileDescriptor? = null
    private var tunnelInput: FileInputStream? = null
    private var tunnelOutput: FileOutputStream? = null

    override fun isRunning(): Boolean = !closed.get()
    override fun trafficCounters(): TrafficCounters = TrafficCounters(bytesUp.get(), bytesDown.get())
    override fun releaseTun() {
        runCatching { tunnelInput?.close() }
        runCatching { tunnelOutput?.close() }
        runCatching { tunnelPfd?.close() }
        tunnelInput = null
        tunnelOutput = null
        tunnelPfd = null
    }

    fun start() {
        if (!profile.encryptionKey.isNullOrBlank()) {
            Mobilecore.setOpenFluxEncryptionKey(profile.encryptionKey)
        } else {
            Mobilecore.setOpenFluxEncryptionKey("")
        }
        val error = Mobilecore.startOpenFlux(profile.documentUrl, profile.transport.value)
        if (!error.isNullOrEmpty()) {
            throw IllegalStateException(error)
        }

        // Wait up to 30s for transport connection
        var connected = false
        for (attempt in 0 until 120) {
            if (closed.get()) return
            if (Mobilecore.isOpenFluxConnected()) {
                connected = true
                break
            }
            val logs = Mobilecore.readOpenFluxLogs()
            if (!logs.isNullOrBlank()) {
                onLog(logs)
            }
            Thread.sleep(250)
        }
        if (!connected) {
            val logs = Mobilecore.readOpenFluxLogs()
            Mobilecore.stopOpenFlux()
            val reason = if (!logs.isNullOrBlank()) ": $logs" else ""
            throw IllegalStateException("Транспорт OpenFlux не подключился за 30 секунд$reason")
        }

        val pfd = establishTun()
        tunnelPfd = pfd
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        tunnelInput = input
        tunnelOutput = output

        workers.execute { readOutgoingPackets(input, dnsServer) }
        workers.execute { writeIncomingPackets(output) }
    }

    private fun readOutgoingPackets(input: FileInputStream, dns: String) {
        val buffer = ByteArray(32767)
        try {
            while (!closed.get()) {
                val length = input.read(buffer)
                if (length <= 0) continue
                val packet = Arrays.copyOf(buffer, length)

                // Enforce per-app and routing policy at packet boundary:
                // Drop and reset packets from unallowed apps trying to bypass routing via SO_BINDTODEVICE tun0
                if (isPacketAllowed != null && !isPacketAllowed.invoke(packet)) {
                    workers.execute {
                        val out = synchronized(outputLock) { tunnelOutput }
                        if (out != null && !closed.get()) {
                            if (isIpv4Tcp(packet)) {
                                val rst = buildTcpRst(packet)
                                if (rst.isNotEmpty()) {
                                    inject(out, rst)
                                }
                            } else if (isIpv4Udp(packet)) {
                                val icmp = buildIcmpPortUnreachable(packet)
                                if (icmp.isNotEmpty()) {
                                    inject(out, icmp)
                                }
                            }
                        }
                    }
                    continue
                }

                if (isIpv4UdpDns(packet)) {
                    workers.execute {
                        val out = synchronized(outputLock) { tunnelOutput }
                        if (out != null && !closed.get()) {
                            forwardDns(out, packet, dns)
                        }
                    }
                } else if (isIpv4Tcp(packet)) {
                    val sendErr = Mobilecore.sendOpenFlux(packet)
                    if (sendErr.isNullOrEmpty()) {
                        bytesUp.addAndGet(packet.size.toLong())
                    }
                } else if (isIpv4Udp(packet)) {
                    workers.execute {
                        val out = synchronized(outputLock) { tunnelOutput }
                        if (out != null && !closed.get()) {
                            val icmp = buildIcmpPortUnreachable(packet)
                            if (icmp.isNotEmpty()) {
                                inject(out, icmp)
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (!closed.get()) onFail("Чтение TUN: ${e.message}")
        }
    }

    private fun writeIncomingPackets(output: FileOutputStream) {
        try {
            while (!closed.get()) {
                val packet = Mobilecore.readOpenFlux()
                if (packet == null || packet.isEmpty()) {
                    Thread.sleep(2)
                    continue
                }
                inject(output, packet)
            }
        } catch (e: Throwable) {
            if (!closed.get()) onFail("Запись TUN: ${e.message}")
        }
    }

    private fun forwardDns(output: FileOutputStream, request: ByteArray, dns: String) {
        val ipHeader = (request[0].toInt() and 0x0f) * 4
        val dnsOffset = ipHeader + 8
        val udpLength = unsignedShort(request, ipHeader + 4)
        if (dnsOffset > request.size || udpLength < 8 || ipHeader + udpLength > request.size) return

        val query = Arrays.copyOfRange(request, dnsOffset, ipHeader + udpLength)
        try {
            val answer = queryDnsOverHttps(query, dns)
            inject(output, buildDnsResponse(request, answer))
        } catch (_: Exception) {
            // DNS resolution failure
        }
    }

    private fun queryDnsOverHttps(query: ByteArray, dns: String): ByteArray {
        val endpoint = when (dns) {
            "8.8.8.8", "8.8.4.4" -> "https://dns.google/dns-query"
            "9.9.9.9", "149.112.112.112" -> "https://dns.9.9.9.9/dns-query"
            "77.88.8.8", "77.88.8.1" -> "https://common.dot.yandex.net/dns-query"
            else -> "https://cloudflare-dns.com/dns-query"
        }

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.setRequestProperty("Accept", "application/dns-message")
        connection.setRequestProperty("Content-Type", "application/dns-message")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(query.size)
        try {
            connection.outputStream.use { it.write(query) }
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw IOException("DoH returned HTTP $statusCode")
            }
            val input: InputStream = connection.inputStream
            val output = ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                if (output.size() > 65535) throw IOException("DNS response too large")
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun inject(output: FileOutputStream, packet: ByteArray) {
        if (closed.get()) return
        synchronized(outputLock) {
            if (!closed.get()) {
                runCatching {
                    output.write(packet)
                    bytesDown.addAndGet(packet.size.toLong())
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            releaseTun()
            Mobilecore.stopOpenFlux()
            workers.shutdownNow()
        }
    }

    companion object {
        private fun isIpv4Tcp(packet: ByteArray): Boolean =
            packet.size >= 20 && (packet[0].toInt() ushr 4) == 4 && (packet[9].toInt() and 0xff) == 6

        private fun isIpv4Udp(packet: ByteArray): Boolean =
            packet.size >= 28 && (packet[0].toInt() ushr 4) == 4 && (packet[9].toInt() and 0xff) == 17

        private fun isIpv4UdpDns(packet: ByteArray): Boolean {
            if (!isIpv4Udp(packet)) return false
            val header = (packet[0].toInt() and 0x0f) * 4
            return header >= 20 && packet.size >= header + 8 && unsignedShort(packet, header + 2) == 53
        }

        internal fun buildTcpRst(orig: ByteArray): ByteArray {
            val ihl = (orig[0].toInt() and 0x0f) * 4
            if (orig.size < ihl + 20) return ByteArray(0)
            val tcpOffset = ihl

            val srcPort = unsignedShort(orig, tcpOffset)
            val dstPort = unsignedShort(orig, tcpOffset + 2)
            val origSeq = unsignedInt(orig, tcpOffset + 4)
            val origAck = unsignedInt(orig, tcpOffset + 8)
            val flags = orig[tcpOffset + 13].toInt() and 0xff
            val isSyn = (flags and 0x02) != 0
            val isAck = (flags and 0x10) != 0
            val isRst = (flags and 0x04) != 0
            if (isRst) return ByteArray(0)

            val payloadLen = orig.size - (ihl + ((orig[tcpOffset + 12].toInt() ushr 4) * 4))
            val segLen = (if (isSyn) 1L else 0L) + payloadLen.coerceAtLeast(0)

            val total = 40
            val rst = ByteArray(total)
            rst[0] = 0x45
            putShort(rst, 2, total)
            putShort(rst, 4, 0)
            putShort(rst, 6, 0x4000)
            rst[8] = 64
            rst[9] = 6
            System.arraycopy(orig, 16, rst, 12, 4)
            System.arraycopy(orig, 12, rst, 16, 4)
            putShort(rst, 10, checksum(rst, 0, 20))

            putShort(rst, 20, dstPort)
            putShort(rst, 22, srcPort)
            if (isAck) {
                putInt(rst, 24, origAck)
                putInt(rst, 28, 0)
                rst[33] = 0x04.toByte()
            } else {
                putInt(rst, 24, 0)
                putInt(rst, 28, (origSeq + segLen) and 0xffffffffL)
                rst[33] = 0x14.toByte()
            }
            rst[32] = 0x50.toByte()
            putShort(rst, 34, 0)
            putShort(rst, 36, tcpChecksum(rst, 20, 20, 12, 16, rst))
            return rst
        }

        internal fun buildIcmpPortUnreachable(orig: ByteArray): ByteArray {
            val ihl = (orig[0].toInt() and 0x0f) * 4
            if (orig.size < ihl + 8) return ByteArray(0)
            val quoteLen = ihl + 8
            val icmpLen = 8 + quoteLen
            val icmp = ByteArray(icmpLen)
            icmp[0] = 3 // Destination Unreachable
            icmp[1] = 3 // Port Unreachable
            System.arraycopy(orig, 0, icmp, 8, quoteLen)
            putShort(icmp, 2, checksum(icmp, 0, icmpLen))

            val total = 20 + icmpLen
            val ip = ByteArray(total)
            ip[0] = 0x45
            putShort(ip, 2, total)
            ip[8] = 64 // TTL
            ip[9] = 1  // ICMP
            System.arraycopy(orig, 16, ip, 12, 4) // src = original dst
            System.arraycopy(orig, 12, ip, 16, 4) // dst = original src
            putShort(ip, 10, checksum(ip, 0, 20))
            System.arraycopy(icmp, 0, ip, 20, icmpLen)
            return ip
        }

        private fun buildDnsResponse(request: ByteArray, dns: ByteArray): ByteArray {
            val requestHeader = (request[0].toInt() and 0x0f) * 4
            val response = ByteArray(20 + 8 + dns.size)
            response[0] = 0x45
            response[1] = request[1]
            putShort(response, 2, response.size)
            response[4] = request[4]
            response[5] = request[5]
            response[8] = 64
            response[9] = 17
            System.arraycopy(request, 16, response, 12, 4)
            System.arraycopy(request, 12, response, 16, 4)
            putShort(response, 10, checksum(response, 0, 20))

            putShort(response, 20, 53)
            putShort(response, 22, unsignedShort(request, requestHeader))
            putShort(response, 24, 8 + dns.size)
            putShort(response, 26, 0)
            System.arraycopy(dns, 0, response, 28, dns.size)
            return response
        }

        private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            while (i < offset + length) {
                val high = bytes[i].toInt() and 0xff
                val low = if (i + 1 < offset + length) bytes[i + 1].toInt() and 0xff else 0
                sum += (high shl 8) or low
                while ((sum and -0x10000) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
                i += 2
            }
            return (sum.inv().toInt()) and 0xffff
        }

        private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

        private fun putInt(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = ((value ushr 24) and 0xff).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xff).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xff).toByte()
            bytes[offset + 3] = (value and 0xff).toByte()
        }

        private fun tcpChecksum(
            packet: ByteArray,
            tcpOffset: Int,
            tcpLen: Int,
            srcIpOffset: Int,
            dstIpOffset: Int,
            ipPacket: ByteArray,
        ): Int {
            var sum = 0L
            for (i in 0 until 4 step 2) {
                sum += ((ipPacket[srcIpOffset + i].toInt() and 0xff) shl 8) or (ipPacket[srcIpOffset + i + 1].toInt() and 0xff)
                sum += ((ipPacket[dstIpOffset + i].toInt() and 0xff) shl 8) or (ipPacket[dstIpOffset + i + 1].toInt() and 0xff)
            }
            sum += 6L
            sum += tcpLen.toLong()
            var i = tcpOffset
            while (i < tcpOffset + tcpLen) {
                val high = packet[i].toInt() and 0xff
                val low = if (i + 1 < tcpOffset + tcpLen) packet[i + 1].toInt() and 0xff else 0
                sum += (high shl 8) or low
                while ((sum and -0x10000) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
                i += 2
            }
            while ((sum and -0x10000) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
            return (sum.inv().toInt()) and 0xffff
        }
    }
}
