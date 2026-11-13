package com.montecarlo.meai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** كل يوم الساعة 10 الصبح بتوقيت عمّان: بيشيك إذا في تقرير جديد وبيبعت إشعار */
class ScoutWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        val r = Repo.latest(applicationContext)
        val prefs = Repo.prefs(applicationContext)
        if (r.date.isNotBlank() && prefs.getString("lastSeen", "") != r.date) {
            prefs.edit().putString("lastSeen", r.date).apply()
            val top = r.ideas.firstOrNull()
            val c = applicationContext
            Notify.show(
                c,
                Lang.t(c, "${r.ideas.size} أفكار جديدة اليوم", "${r.ideas.size} new app ideas today"),
                if (top != null) Lang.t(c, "الأقوى: ${top.name}، ${top.pitch}", "Top pick: ${top.name}. ${top.pitch}") else r.summary
            )
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        fun schedule(ctx: Context) {
            val now = ZonedDateTime.now(ZoneId.of("Asia/Amman"))
            var next = now.withHour(10).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<ScoutWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("daily-scout", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
