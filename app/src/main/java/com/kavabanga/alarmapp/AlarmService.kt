package com.kavabanga.alarmapp

import android.app.*
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.*
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.content.Context
import android.provider.Settings
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.ContextCompat



class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastPingTime = System.currentTimeMillis()
    private val PING_TIMEOUT = 30000L // 30 секунд

    // Временная метка, до которой действует режим тишины
    private var snoozeUntil = 0L 

    companion object {
        const val ACTION_MUTE = "com.kavabanga.alarmapp.ACTION_MUTE"
        const val EXTRA_MUTE_DURATION_MS = "mute_duration_ms"
    }

    // Обработка команд приглушения (из Activity или уведомлений)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MUTE) {
            // Получаем интервал приглушения (по умолчанию 5 минут)
            val duration = intent.getLongExtra(EXTRA_MUTE_DURATION_MS, 5 * 60 * 1000L)
            muteAlarm(duration)
        }
        return START_STICKY
    }

    private fun muteAlarm(durationMs: Long) {
        // Задаем время, до которого тревога выключена
        snoozeUntil = System.currentTimeMillis() + durationMs
        
        // Выключаем звук, если он играет прямо сейчас
        stopAlarmSound()
        
        // Обновляем статусное уведомление, чтобы пользователь видел, сколько осталось молчать
        val minutes = durationMs / 1000 / 60
        startForegroundServiceNotification("В режиме тишины на $minutes мин.")
        
        // Планируем возврат в нормальный режим (обновление статус-бара) по истечении таймера
        handler.postDelayed({
            if (System.currentTimeMillis() >= snoozeUntil) {
                startForegroundServiceNotification("Мониторинг сервера запущен")
            }
        }, durationMs)
    }

    // Проверяем, находится ли приложение в режиме тишины прямо сейчас
    private fun isMuted(): Boolean {
        return System.currentTimeMillis() < snoozeUntil
    }

    // Обновленный таймер проверки связи
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastPingTime > PING_TIMEOUT) {
                if (!isMuted()) {
                    triggerAlarm("Потеряна связь с сервером!")
                }
            }
            // Продолжаем проверять каждые 5 секунд в любом случае
            handler.postDelayed(this, 5000) 
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Этот код нужно вызвать в самом начале onCreate() или onStartCommand()
        val channelId = "alarm_service_channel"
        
        // 1. Создаем канал уведомлений (ОБЯЗАТЕЛЬНО для Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Фоновая работа службы тревоги",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о работе серверной тревоги"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // 2. Проверяем разрешение. Если его нет — перекидываем пользователя в настройки
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(settingsIntent)
            }
        }
        startForegroundServiceNotification("Мониторинг сервера запущен")
        startWebSocket()
        handler.post(watchdogRunnable)
    }

    private fun startWebSocket() {
        val request = Request.Builder().url("wss://://your-server.com").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastPingTime = System.currentTimeMillis() 

                // Даже если мы приглушены, мы обновляем lastPingTime, чтобы при выходе из тишины не сработал ложный watchdog

                when {
                    text == "PING" -> { /* Игнорируем */ }
                    text.startsWith("ALARM:") -> {
                        val reason = text.substringAfter("ALARM:")
                        if (!isMuted()) {
                            triggerAlarm("ТРЕВОГА: $reason")
                        }
                    }
                    text.startsWith("TEXT:") -> {
                        val message = text.substringAfter("TEXT:")
                        // Текстовые уведомления выводим ВСЕГДА, они не шумят бесконечно
                        showTextNotification("Сообщение от сервера", message)
                    }
                }
            }
        })
    }

    private fun triggerAlarm(message: String) {
        showTextNotification("ВНИМАНИЕ", message)

        if (mediaPlayer == null || !mediaPlayer!!.isPlaying) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            
            mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                isLooping = true
                start()
            }
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }
    
    // Функция для вызова из клика по кнопке "Приглушить на 15 минут"
    fun muteAlarmForFifteenMinutes() {
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_MUTE
            // 15 минут в миллисекундах: 15 * 60 * 1000
            putExtra(AlarmService.EXTRA_MUTE_DURATION_MS, 15L * 60L * 1000L) 
        }
        startService(intent)
    }
    
    private fun showTextNotification(title: String, message: String) {
        val channelId = "server_alerts"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(channelId, "Alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
    
        // 1. Создаем намерение, которое сработает при клике на кнопку приглушения
        val muteIntent = Intent(this, AlarmMuteReceiver::class.java)
        
        // Флаг IMMUTABLE обязателен в современных версиях Android для безопасности
        val mutePendingIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            muteIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    
        // 2. Строим само уведомление с кнопкой действия
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // Уведомление исчезнет, если на него нажать
            // Добавляем кнопку: иконка (опционально), Текст кнопки, само действие
            .addAction(
//                android.R.drawable.ic_media_volume_mute, 
                android.R.drawable.ic_lock_silent_mode_off,
                "Приглушить на 15 мин", 
                mutePendingIntent
            )
            .build()
    
        // Отправляем пуш на экран
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }


    private fun startForegroundServiceNotification(statusText: String) {
        val channelId = "service_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Status", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Сервис контроля связи")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        webSocket?.close(1000, "Service destroyed")
        stopAlarmSound()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
