package com.example.usbrfid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Network

import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.NonCancellable.isActive


class UsbSerialService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var port: UsbSerialPort? = null
    private val TAG = "UsbSerialService"

    // TODO: замените на ваш HTTPS endpoint
    private val serverUrl = "https://yourserver.example/api/rfid"

    // Добавляем функцию toHex
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        scope.launch { initAndRead() }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "usb_rfid_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "USB RFID", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("USB RFID Service")
            .setContentText("Reading USB serial device...")
            .setContentIntent(pending)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, n)
    }

    private suspend fun initAndRead() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.i(TAG, "No USB serial drivers found")
            return
        }
        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.i(TAG, "Could not open device. Permission likely missing")
            return
        }

        port = driver.ports.firstOrNull()
        if (port == null) {
            Log.e(TAG, "No ports available in driver")
            connection.close()
            return
        }

        try {
            port!!.open(connection)
            port!!.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            readLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening port", e)
        } finally {
            try {
                port?.close()
                connection.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun readLoop() {
        val localPort = port ?: return
        val buffer = ByteArray(1024)

        // Исправленная проверка
        while (isActive) {
            try {
                val len = localPort.read(buffer, 2000)
                if (len > 0) {
                    val data = buffer.copyOf(len)
                    handleRawTagData(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error", e)
                delay(1000)
            }
        }
    }

    private suspend fun handleRawTagData(data: ByteArray) {
        val hex = data.toHex() // Теперь работает
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val json = "{\"uid_hex\":\"$hex\",\"uid_b64\":\"$b64\",\"raw_len\":${data.size},\"source\":\"usb_serial\"}"
        try {
            val res = Network.postJson(serverUrl, json)
            res.onSuccess { Log.i(TAG, "POST OK: $it") }
            res.onFailure { Log.e(TAG, "POST failed", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { port?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}