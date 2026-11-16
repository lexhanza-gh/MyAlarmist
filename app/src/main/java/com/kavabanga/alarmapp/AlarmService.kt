package com.kavabanga.alarmapp

import android.Manifest
import android.app.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.util.Log
import okhttp3.*

/*
Развернуто вместо звёздочек
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
*/



class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastPingTime = System.currentTimeMillis()
    private val PING_TIMEOUT = 30000L // 30 секунд

    // Временная метка, до которой действует режим тишины
    private var snoozeUntil = 0L 
	// Переменная для хранения задачи возврата из режима тишины
	private var unmuteRunnable: Runnable? = null

    companion object {
		const val serverUrl = "wss://tryit.linkpc.net:53333"
        const val ACTION_MUTE = "com.kavabanga.alarmapp.ACTION_MUTE"
        const val ACTION_STOP_SOUND = "com.kavabanga.alarmapp.ACTION_STOP_SOUND"
        // Команда для полной остановки службы мониторинга
        const val ACTION_STOP_SERVICE = "com.kavabanga.alarmapp.ACTION_STOP_SERVICE"
        // Команда для повторного подключения к серверу без перезапуска watchdog
        const val ACTION_RESTART_SERVICE = "com.kavabanga.alarmapp.ACTION_RESTART_SERVICE"
        const val EXTRA_MUTE_DURATION_MS = "mute_duration_ms"
		const val CHANNEL_STATUS_ID = "service_status_channel"
        const val CHANNEL_ALARM_ID = "alarm_alert_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val ALARM_NOTIFICATION_ID = 2
        private const val TAG = "AlarmService"

        // Остановить службу сообщений (мониторинга) из Activity или другого компонента
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }

        // Запустить службу, если она остановлена, или переподключить WebSocket, если она уже работает
        fun restart(context: Context) {
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_RESTART_SERVICE)
            ContextCompat.startForegroundService(context, intent)
        }
    }


/* ----------------------------------------------------------------------------------------------
// 						БЛОК УПРАВЛЕНИЯ СООБЩЕНИЯМИ И ТРЕВОГОЙ
---------------------------------------------------------------------------------------------- */

    private fun createNotificationChannels() {
		
        // 1. Создаем канал уведомлений (ОБЯЗАТЕЛЬНО для Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Тихий канал для фоновой работы
            val statusChannel = NotificationChannel(
                CHANNEL_STATUS_ID,
                "Статус фоновой службы",
                NotificationManager.IMPORTANCE_MIN // Без звука и всплывающих окон, Минимальный приоритет скрывает иконку вверху экрана
            ).apply {
                description = "Показывает, что мониторинг активен"
				setShowBadge(false) // Убирает точку уведомления с иконки приложения
            }

            // Громкий канал для самой тревоги
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Экстренные тревоги",
                NotificationManager.IMPORTANCE_HIGH // Со звуком и баннером поверх экрана
            ).apply {
                description = "Громкие уведомления при срабатывании тревоги"
                enableVibration(true)
            }


            // manager.createNotificationChannel(serviceChannel)
            // manager.createNotificationChannel(alertsChannel)
			// ??
            manager.createNotificationChannels(listOf(statusChannel, alarmChannel))
        }
    }

    private fun startForegroundServiceNotification(statusText: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setContentTitle("Служба мониторинга")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off) // Замените на вашу иконку
            .setOngoing(true) // Запрещает пользователю смахнуть уведомление
			.setSilent(true) // Гарантирует отсутствие звука и вибрации
			.setPriority(NotificationCompat.PRIORITY_MIN)			
            .build()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    // Вызывайте этот метод, когда придет реальная тревога
    private fun showAlarmNotification(alarmTitle: String, alarmMessage: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. Создаем намерение, которое сработает при клике на кнопку приглушения
        val muteIntent = Intent(this, AlarmMuteReceiver::class.java)
        
        // Флаг IMMUTABLE обязателен в современных версиях Android для безопасности
        val mutePendingIntent = PendingIntent.getBroadcast(this, 0, muteIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) 
 
        // 2. Строим само уведомление с кнопкой действия
        val alarmNotification = NotificationCompat.Builder(this, CHANNEL_ALARM_ID)
            .setContentTitle(alarmTitle)
            .setContentText(alarmMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Замените на иконку тревоги
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Для Android 7.1 и ниже
            .setAutoCancel(true)// Уведомление исчезнет, если на него нажать
            // Добавляем кнопку: иконка (опционально), Текст кнопки, само действие
            .addAction(
//              android.R.drawable.ic_media_volume_mute, 
                android.R.drawable.ic_lock_silent_mode_off,
                "Приглушить на 15 мин", 
                mutePendingIntent
            )
            .build()
			
		// Отправляем пуш на экран
        manager.notify(ALARM_NOTIFICATION_ID, alarmNotification)
    }

	private fun triggerAlarm(message: String) {
		// 1. Обязательно проверяем режим тишины!
		if (isMuted()) {
			// Можно просто показать тихое уведомление без звука или вообще проигнорировать
			return 
		}

		showAlarmNotification("ВНИМАНИЕ! ТРЕВОГА!",message)

		// 2. Если сирена уже орет — не перезапускаем её заново
		if (mediaPlayer?.isPlaying == true) return

		// Очищаем старый плеер, если он вдруг завис в памяти
		stopAlarmSound()

		val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
			?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

		// 3. Настраиваем плеер на канал БУДИЛЬНИКА (играет даже в беззвучном режиме)
		try {
			mediaPlayer = MediaPlayer().apply {
				setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ALARM)
						.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build()
				)
				setDataSource(this@AlarmService, alarmUri)
				isLooping = true
				prepare()
				start()
			}
		} catch (e: Exception) {
			e.printStackTrace()
			mediaPlayer = null
		} finally {
		}
	}

	private fun stopAlarmSound() {
		try {
			mediaPlayer?.let {
				if (it.isPlaying) {
					it.stop()
				}
				it.release()
			}
		} catch (e: Exception) {
			e.printStackTrace()
		} finally {
			mediaPlayer = null
		}
	}
    	
	private fun muteAlarm(durationMs: Long) {
		// Задаем время, до которого тревога выключена
		snoozeUntil = System.currentTimeMillis() + durationMs
		// Выключаем звук, если он играет прямо сейчас
		stopAlarmSound()

		// Обновляем статусное уведомление, чтобы пользователь видел, сколько осталось молчать
		val minutes = durationMs / 1000 / 60
		startForegroundServiceNotification("В режиме тишины на $minutes мин.")

		// Отменяем предыдущий таймер, если кнопку нажали повторно
		unmuteRunnable?.let { handler.removeCallbacks(it) }

        // Планируем возврат в нормальный режим (обновление статус-бара) по истечении таймера
		unmuteRunnable = Runnable {
			if (System.currentTimeMillis() >= snoozeUntil) {
				startForegroundServiceNotification("Мониторинг сервера запущен")
			}
		}
		handler.postDelayed(unmuteRunnable!!, durationMs)
	}

    // Проверяем, находится ли приложение в режиме тишины прямо сейчас
    private fun isMuted(): Boolean {
        return System.currentTimeMillis() < snoozeUntil
    }

/* ----------------------------------------------------------------------------------------------
// 						КОНЕЦ БЛОКА УПРАВЛЕНИЯ ТРЕВОГОЙ
---------------------------------------------------------------------------------------------- */


/* ----------------------------------------------------------------------------------------------
// 						БЛОК ЛОГИКИ ВЫПОЛНЕНИЯ
---------------------------------------------------------------------------------------------- */

    override fun onCreate() {
        super.onCreate()
        
        // 1. Создаем оба канала один раз при старте сервиса
        createNotificationChannels()
        
        // 2. Запускаем Foreground-режим на ТИХОМ канале
        startForegroundServiceNotification("Мониторинг сервера запущен")
        
        startWebSocket()
        handler.post(watchdogRunnable) 
    }
	
    // Обработка команд приглушения, остановки и перезапуска службы
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> {
                // Получаем интервал приглушения (по умолчанию 5 минут)
                val duration = intent.getLongExtra(EXTRA_MUTE_DURATION_MS, 5 * 60 * 1000L)
                muteAlarm(duration)
            }
            ACTION_STOP_SOUND -> {
                // Просто выключаем сирену, но не включаем режим тишины на будущее
                stopAlarmSound()
                //manager.cancel(ALARM_NOTIFICATION_ID)
            }
            ACTION_STOP_SERVICE -> {
                // Полностью останавливаем службу и закрываем соединение в onDestroy()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_RESTART_SERVICE -> {
                // Повторно подключаемся к серверу, не создавая второй watchdog
                restartMessageConnection()
            }
        }
		// START_STICKY перезапустит службу, если система убьет её из-за нехватки памяти
        return START_STICKY
    }


    // Таймер проверки связи
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastPingTime > PING_TIMEOUT) {
                if (!isMuted()) {
                    triggerAlarm("Потеряна связь с сервером!")
                }
            }
            // Продолжаем проверять каждые 5 секунд
            handler.postDelayed(this, 5000) 
        }
    }

    // Закрывает текущее соединение и открывает новое, не перезапуская службу целиком
    private fun restartMessageConnection() {
        webSocket?.cancel()
        webSocket = null
        lastPingTime = System.currentTimeMillis()
        startWebSocket()
    }

    private fun startWebSocket() {
		
        var request = try {
			Request.Builder().url(serverUrl).build()
		} catch (e: Exception) {
            Log.e(TAG, "Некорректный URL сервера: $serverUrl", e)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Даже если мы приглушены, мы обновляем lastPingTime, чтобы при выходе из тишины не сработал ложный watchdog
                lastPingTime = System.currentTimeMillis() 
				// Переключаемся в главный поток для безопасной работы со звуком и уведомлениями
                handler.post {
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
							showAlarmNotification("Сообщение от сервера", message)
						}
					}
                }
            }
			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Ошибка WebSocket: ${t.message}")
            }

        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        // Отменяем таймер выхода из режима тишины при завершении службы
        unmuteRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        stopAlarmSound()
        super.onDestroy()
    }
	
    override fun onBind(intent: Intent?): IBinder? = null

/* ----------------------------------------------------------------------------------------------
// 						КОНЕЦ БЛОКА ЛОГИКИ
---------------------------------------------------------------------------------------------- */

}



