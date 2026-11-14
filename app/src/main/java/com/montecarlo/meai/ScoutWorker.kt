package com.montecarlo.meai

import android.content.Context
import android.content.Intent
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
        checkJobs()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    /** إشعار تاني إذا في فرص شغل جديدة اليوم */
    private fun checkJobs() {
        val c = applicationContext
        val j = runCatching { Jobs.latest(c) }.getOrNull() ?: return
        val prefs = Repo.prefs(c)
        if (j.date.isBlank() || j.leads.isEmpty() || prefs.getString("jobsSeen", "") == j.date) return
        prefs.edit().putString("jobsSeen", j.date).apply()
        val top = j.leads.first()
        Notify.show(
            c,
            Lang.t(c, "💼 ${j.leads.size} فرص شغل جديدة", "💼 ${j.leads.size} new work leads"),
            Lang.t(c, "الأنسب: ${top.title}", "Best fit: ${top.title}") +
                (if (top.budget.isNotBlank()) " · ${top.budget}" else ""),
            Intent(c, JobsActivity::class.java),
            2
        )
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
