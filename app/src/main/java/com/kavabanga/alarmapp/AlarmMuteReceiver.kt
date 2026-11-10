package com.example.alarmapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmMuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Перенаправляем команду приглушения в сервис
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_MUTE
            // Задаем интервал (например, 15 минут)
            putExtra(AlarmService.EXTRA_MUTE_DURATION_MS, 15L * 60L * 1000L)
        }
        context.startService(serviceIntent)
    }
}
