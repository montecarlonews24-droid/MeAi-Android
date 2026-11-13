package com.montecarlo.meai

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** طلبات "ابنيها" — بتنحفظ بـ Firebase والـ Planner بيشتغل عليها */
object Builds {
    private const val DB = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/builds/"

    fun requested(ctx: Context, name: String) = Repo.prefs(ctx).contains("build:" + Feedback.key(name))

    fun request(ctx: Context, idea: Idea): Boolean {
        val k = Feedback.key(idea.name)
        val c = URL("$DB$k.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        return try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("name", idea.name)
                .put("pitch", idea.pitch.take(300))
                .put("problem", idea.problem.take(400))
                .put("audience", idea.audience.take(300))
                .put("buildDays", idea.days)
                .put("ts", System.currentTimeMillis())
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            if (ok) Repo.prefs(ctx).edit().putLong("build:$k", System.currentTimeMillis()).apply()
            ok
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }
}

/** بيشيك كل 10 دقايق إذا الخطة جهزت، وبيبعت إشعار */
class PlanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val name = inputData.getString("name") ?: return Result.failure()
        return try {
            if (Repo.plan(Feedback.key(name)) != null) {
                val open = Intent(applicationContext, PlanActivity::class.java).putExtra("name", name)
                val c = applicationContext
                Notify.show(c,
                    Lang.t(c, "🔨 خطة $name جاهزة", "🔨 $name build plan is ready"),
                    Lang.t(c, "محمد خلّص خطة البناء. افتحها وانسخ أول رسالة لـ Claude.", "Your build plan is ready. Open it and copy the first prompt for Claude."),
                    open, name.hashCode())
                Result.success()
            } else if (runAttemptCount >= 12) Result.failure() else Result.retry()
        } catch (e: Exception) {
            if (runAttemptCount >= 12) Result.failure() else Result.retry()
        }
    }

    companion object {
        fun start(ctx: Context, name: String) {
            val req = OneTimeWorkRequestBuilder<PlanWorker>()
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("name" to name))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("plan-" + Feedback.key(name), ExistingWorkPolicy.REPLACE, req)
        }
    }
}
