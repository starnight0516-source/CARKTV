package com.car.taiwanktv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object QrCodeHelper {

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackIp: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        // 優先回傳 Wi-Fi / 熱點 / 有線網路介面 IP
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth") || name.startsWith("swlan")) {
                            return ip
                        }
                        if (fallbackIp == null) fallbackIp = ip
                    }
                }
            }
            return fallbackIp
        } catch (_: Exception) {
            return null
        }
    }

    fun generateQrBitmap(content: String, size: Int = 520): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2
            )
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }
}
