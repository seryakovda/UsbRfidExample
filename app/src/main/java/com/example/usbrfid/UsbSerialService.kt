package com.example.usbrfid

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class UsbSerialService : Service() {
    companion object {
        private const val TAG = "UsbSerialService"
        private const val ACTION_USB_PERMISSION = "com.example.usbrfid.USB_PERMISSION"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var usbManager: UsbManager
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null

    // Замени на свой защищённый endpoint (https)
    private val serverUrl = "https://yourserver.example/api/rfid"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: granted=$granted for device=${device?.deviceName}")
                if (granted && device != null) {
                    // пробуем открыть устройство (повторный шаг после получения permission)
                    openDevice(device)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // регистрируем receiver для обработки разрешения
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        // стартуем (если нужно — делай foreground для стабильной работы на Android O+)
        startForegroundIfNeeded()
        // начинаем обнаружение/запрос разрешения
        discoverAndRequest()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundIfNeeded() {
        // Небольшой foreground-заголовок для сервисов на современных Android
        // (Можно опустить, если сервис запускается из Activity и не требуется всегда активный)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chId = "usb_rfid_channel"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = android.app.NotificationChannel(chId, "USB RFID", android.app.NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val p = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(this, chId)
                .setContentTitle("USB RFID")
                .setContentText("Service running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(p)
                .build()
            startForeground(1, n)
        }
    }

    private fun discoverAndRequest() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Log.i(TAG, "No USB serial drivers found")
            return
        }
        // Берём первое устройство (можно фильтровать по VID/PID)
        val driver = drivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            // Запрос разрешения через Broadcast PendingIntent — не нужен MainActivity
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
            Log.i(TAG, "Requested USB permission for ${device.deviceName}")
            return
        }

        // если permission уже есть — открываем
        openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        try {
            // Находим драйвер, соответствующий устройству (повторно)
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device.deviceId == device.deviceId }
            if (driver == null) {
                Log.e(TAG, "Driver for device not found after permission")
                return
            }

            connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                Log.e(TAG, "openDevice returned null (permission?)")
                return
            }

            port = driver.ports.firstOrNull()
            if (port == null) {
                Log.e(TAG, "No ports in driver")
                connection?.close()
                connection = null
                return
            }

            // Настраиваем порт — baudrate/паритет могут отличаться для конкретного ридера
            port!!.open(connection)
            port!!.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.i(TAG, "Port opened, starting read loop")
            startReadingLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening device: ${e.message}", e)
            try { port?.close() } catch (_: Exception) {}
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    private fun startReadingLoop() {
        scope.launch {
            val buf = ByteArray(1024)
            while (isActive) {
                try {
                    val len = port?.read(buf, 2000) ?: 0
                    if (len > 0) {
                        val dataBytes = buf.copyOf(len)
                        val hex = dataBytes.toHex()
                        Log.i(TAG, "Read ($len): $hex")

                        // Формируем JSON и отправляем (suspend)
                        val json = """{"uid_hex":"$hex","raw_len":$len,"source":"usb_serial"}"""
                        try {
                            val resp = postJson(serverUrl, json)
                            Log.i(TAG, "POST ok: $resp")
                        } catch (ne: Exception) {
                            Log.e(TAG, "POST failed: ${ne.message}")
                        }
                    }
                } catch (io: IOException) {
                    Log.e(TAG, "Read error: ${io.message}")
                    delay(1000)
                } catch (t: Throwable) {
                    Log.e(TAG, "Unexpected error in read loop: ${t.message}", t)
                    delay(1000)
                }
            }
        }
    }

    // Простая suspend-функция POST через OkHttp, возвращает тело как String
    private val httpClient = OkHttpClient()
    private suspend fun postJson(url: String, json: String): String = withContext(Dispatchers.IO) {
        val media = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(media)
        val req = Request.Builder().url(url).post(body).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return@withContext resp.body?.string() ?: ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(permissionReceiver)
        scope.cancel()
        try { port?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
    }
}

// утилита hex
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }