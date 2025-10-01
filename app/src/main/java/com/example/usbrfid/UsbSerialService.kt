package com.example.usbrfid

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.cancel
import java.io.IOException

class UsbSerialService : Service() {

    private var usbManager: UsbManager? = null
    private var driver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val serverUrl = "http://yourserver.com/receiver.php" // URL твоего PHP сервера

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        openConnection()
    }

    private fun openConnection() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.e("UsbSerialService", "Нет доступных драйверов")
            return
        }

        driver = availableDrivers[0]
        val device: UsbDevice = driver!!.device

        // PendingIntent ссылается на MainActivity
        val permissionIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        if (!usbManager!!.hasPermission(device)) {
            usbManager!!.requestPermission(device, permissionIntent)
            return
        }

        connection = usbManager!!.openDevice(driver!!.device)
        if (connection == null) {
            Log.e("UsbSerialService", "Не удалось открыть соединение")
            return
        }

        port = driver!!.ports[0] // первый порт
        try {
            port!!.open(connection)
            port!!.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            startReading()
        } catch (e: IOException) {
            Log.e("UsbSerialService", "Ошибка открытия порта: ${e.message}")
        }
    }

    private fun startReading() {
        scope.launch {
            val buffer = ByteArray(64)
            while (isActive) {
                try {
                    val numBytes = port?.read(buffer, 1000) ?: 0
                    if (numBytes > 0) {
                        val data = buffer.copyOf(numBytes).toHex()
                        Log.d("UsbSerialService", "Считано: $data")

                        // Отправляем на сервер
                        Network.post(serverUrl, data)
                    }
                } catch (e: IOException) {
                    Log.e("UsbSerialService", "Ошибка чтения: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            port?.close()
        } catch (e: IOException) {
            Log.e("UsbSerialService", "Ошибка закрытия порта: ${e.message}")
        }
    }
}

// --- Утилита для перевода байтов в HEX ---
fun ByteArray.toHex(): String =
    joinToString(separator = " ") { "%02X".format(it) }