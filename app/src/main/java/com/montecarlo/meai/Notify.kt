package com.montecarlo.meai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notify {
    private const val CH = "daily_ideas"
    // فرص الشغل: قناة بأهمية عالية (صوت + اهتزاز + بتنزل فوق الشاشة)، عشان ما تروح فرصة
    private const val JOBS = "job_alerts"
    // للسجل: عناوين إشعارات الفرص اللي طلعت من آخر فحص
    private val posted = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun drain(): String = synchronized(posted) { val t = posted.joinToString(" | "); posted.clear(); t }
    private val JOB_IDS = setOf(2, 3, 4, 5, 6) // 2 = فرص الـ Agent، 3 = خمسات، 4 = مستقل، 5 = Freelancer، 6 = LinkedIn

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, Lang.t(ctx, "أفكار اليوم", "Daily ideas"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(ctx: Context, title: String, text: String, intent: Intent? = null, id: Int = 1) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channel(ctx)
        val loud = id in JOB_IDS
        if (loud) posted.add(title.take(80))
        if (loud) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(JOBS, Lang.t(ctx, "فرص شغل جديدة", "New work leads"), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, id, intent ?: Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, if (loud) JOBS else CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
