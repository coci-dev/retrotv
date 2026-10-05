package com.joel.retrotv.server

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object Network {

    /** IPv4 de la TV en la red local (Wi-Fi o Ethernet), o null si no hay red. */
    fun localIpv4(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
            .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }
        // Preferimos Wi-Fi/Ethernet sobre interfaces raras (VPN, docker, etc.).
        return (candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("eth") }
            ?: candidates.firstOrNull())?.second?.hostAddress
    }

    fun controllerUrl(ip: String) = "http://$ip:${ControllerServer.PORT}/"

    fun qrBitmap(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
