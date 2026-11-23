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
import java.util.concurrent.TimeUnit

/**
 * كل ساعة بيشيك إذا الـ Job Scout خلّص جولة جديدة (الصبح 8:30 والمسا 5:30)،
 * وإذا لقى فرص جديدة بيبعت إشعار. يعني إشعار لكل جولة فيها إشي جديد.
 */
class JobsWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        check(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        /** الفحص نفسه: بيشتغل من WorkManager ومن المنبّه (Keeper) */
        fun check(c: Context) {
        if (!busy.compareAndSet(false, true)) return
        try {
        // أول إشي: منرفع بوستات Reddit الجديدة لـ Firebase عشان الـ Agent يحكم عليها
        runCatching { RedditRelay.sync(c) }
        // وبحث LinkedIn من الموبايل (LinkedIn بيسكّر على سيرفرات GitHub)
        runCatching { LinkedInRelay.sync(c) }
        // ومشاريع مستقل من نت الموبايل (مستقل بيسكّر على سيرفرات GitHub)
        runCatching { MostaqlRelay.sync(c) }
        // وطلبات خمسات بنفس الطريقة
        runCatching { KhamsatRelay.sync(c) }
        // ومشاريع Freelancer: إشعار فوري للمشاريع الجديدة (المشاريع هناك بتنزحم خلال ساعة)
        runCatching { FreelancerRelay.sync(c) }
        val j = Jobs.latest(c)
        val prefs = Repo.prefs(c)
        val run = j.generatedAt.ifBlank { j.date }
        if (run.isNotBlank() && j.fresh > 0 && j.leads.isNotEmpty() && prefs.getString("jobsSeenAt", "") != run) {
            prefs.edit().putString("jobsSeenAt", run).apply()
            val top = j.leads.first()
            Notify.show(
                c,
                Lang.t(c, "💼 ${j.fresh} فرص شغل جديدة", "💼 ${j.fresh} new work leads"),
                Lang.t(c, "الأنسب: ${top.title}", "Best fit: ${top.title}") +
                    (if (top.budget.isNotBlank()) " · ${top.budget}" else ""),
                Intent(c, JobsActivity::class.java),
                2
            )
        }
        } finally {
            busy.set(false)
        }
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<JobsWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("jobs-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
