package com.yourname.classroomagent

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LocalVpnService : VpnService() {

    companion object {
        @Volatile var allowedDomains: Set<String> = emptySet()
        var isRunning = false
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP = "STOP_VPN"

        fun updateAllowedDomains(domains: Set<String>) {
            Log.d("VpnService", "updateAllowedDomains 호출: $domains")
            allowedDomains = domains.toHashSet()
            Log.d("VpnService", "업데이트 후 allowedDomains: $allowedDomains")
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val builder = Builder()
            .setSession("ClassroomAgent VPN")
            .addAddress("10.0.0.1", 32)
            .addDnsServer("10.0.0.2")
            .addRoute("10.0.0.2", 32)  // Only route DNS server traffic through VPN
            .setMtu(1500)

        vpnInterface = builder.establish() ?: return
        isRunning = true
        running = true

        Thread({ runVpnLoop() }, "vpn-loop").start()
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
    }

    private fun runVpnLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val packet = ByteArray(32767)

        while (running) {
            val length = input.read(packet)
            if (length <= 0) continue

            if (!isDnsQuery(packet, length)) continue

            val domain = extractDomain(packet, length)
            if (domain != null) {
                if (isAllowed(domain)) {
                    Log.d("VpnService", "DNS 전달 시작: $domain")
                    val response = forwardDnsQuery(packet, length)
                    if (response != null) {
                        output.write(response)
                        Log.d("VpnService", "DNS 전달 완료: $domain")
                    } else {
                        Log.e("VpnService", "DNS 전달 실패: $domain")
                    }
                } else {
                    Log.d("VpnService", "차단된 도메인: $domain")
                    val blocked = buildNxdomainResponse(packet, length)
                    if (blocked != null) output.write(blocked)
                }
            }
        }
    }

    // ── Packet inspection ────────────────────────────────────────────────────

    private fun isDnsQuery(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false
        if (packet[9].toInt() and 0xFF != 17) return false   // UDP only
        val destPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
        return destPort == 53
    }

    /** Parses the QNAME in the DNS payload.
     *  IP header = 20 bytes, UDP header = 8 bytes, DNS header = 12 bytes → offset 40 */
    private fun extractDomain(packet: ByteArray, length: Int): String? {
        val offset = 40
        if (length <= offset) return null
        return try {
            val sb = StringBuilder()
            var i = offset
            while (i < length && packet[i] != 0.toByte()) {
                val labelLen = packet[i].toInt() and 0xFF
                i++
                if (i + labelLen > length) break
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(packet, i, labelLen, Charsets.UTF_8))
                i += labelLen
            }
            sb.toString().lowercase()
        } catch (e: Exception) { null }
    }

    private fun isAllowed(domain: String): Boolean {
        val domains = allowedDomains
        Log.d("VpnService", "isAllowed 체크: $domain, allowedDomains: $domains")
        if (domains.contains("*")) {
            Log.d("VpnService", "와일드카드 허용: $domain")
            return true
        }
        if (domains.isEmpty()) {
            Log.d("VpnService", "목록 비어있음, 차단: $domain")
            return false
        }
        return domains.any { allowed ->
            domain == allowed || domain.endsWith(".$allowed")
        }
    }

    // ── DNS forwarding ───────────────────────────────────────────────────────

    private fun forwardDnsQuery(packet: ByteArray, length: Int): ByteArray? {
        return try {
            val dnsPayload = packet.copyOfRange(28, length)
            val socket = DatagramSocket()
            protect(socket)
            Log.d("VpnService", "소켓 protect 완료")

            socket.soTimeout = 500
            val request = DatagramPacket(
                dnsPayload, dnsPayload.size,
                InetAddress.getByName("8.8.8.8"), 53
            )
            socket.send(request)
            Log.d("VpnService", "DNS 요청 전송 완료")

            val responseBuffer = ByteArray(512)
            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(response)
            Log.d("VpnService", "DNS 응답 수신: ${response.length} bytes")
            socket.close()

            val result = buildIpUdpResponse(packet, length, responseBuffer, response.length)
            Log.d("VpnService", "패킷 재조립 완료: ${result?.size} bytes")
            result
        } catch (e: Exception) {
            Log.e("VpnService", "forwardDnsQuery 실패", e)
            null
        }
    }

    // ── Packet construction ──────────────────────────────────────────────────

    private fun buildIpUdpResponse(
        query: ByteArray, queryLen: Int,
        dnsResponse: ByteArray, dnsLen: Int
    ): ByteArray {
        val totalLen = 28 + dnsLen
        val result = ByteArray(totalLen)

        // IP header
        result[0] = 0x45.toByte()           // Version=4, IHL=5
        result[1] = 0x00
        result[2] = (totalLen shr 8).toByte()
        result[3] = (totalLen and 0xFF).toByte()
        result[4] = query[4]                // ID (copy from query)
        result[5] = query[5]
        result[6] = 0x00
        result[7] = 0x00
        result[8] = 0x40.toByte()           // TTL=64
        result[9] = 0x11                    // Protocol=UDP
        result[10] = 0x00                   // Checksum (calculated below)
        result[11] = 0x00

        // src IP = original dst IP (10.0.0.2, our fake DNS)
        result[12] = query[16]
        result[13] = query[17]
        result[14] = query[18]
        result[15] = query[19]

        // dst IP = original src IP
        result[16] = query[12]
        result[17] = query[13]
        result[18] = query[14]
        result[19] = query[15]

        // IP checksum
        val ipChecksum = calculateChecksum(result, 0, 20)
        result[10] = (ipChecksum shr 8).toByte()
        result[11] = (ipChecksum and 0xFF).toByte()

        // UDP header
        val udpLen = 8 + dnsLen
        result[20] = query[22]              // src port = original dst port (53)
        result[21] = query[23]
        result[22] = query[20]              // dst port = original src port
        result[23] = query[21]
        result[24] = (udpLen shr 8).toByte()
        result[25] = (udpLen and 0xFF).toByte()
        result[26] = 0x00                   // UDP checksum (0 = disabled)
        result[27] = 0x00

        // DNS payload
        System.arraycopy(dnsResponse, 0, result, 28, dnsLen)

        return result
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        // Handle odd-length buffer
        if ((offset + length) % 2 != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun buildNxdomainResponse(query: ByteArray, length: Int): ByteArray? {
        if (length < 40) return null
        val dns = query.copyOfRange(28, length)
        dns[2] = (dns[2].toInt() or 0x80).toByte()    // QR = 1 (response)
        dns[3] = (dns[3].toInt() or 0x03).toByte()    // RCODE = 3 (NXDOMAIN)
        return buildIpUdpResponse(query, length, dns, dns.size)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
