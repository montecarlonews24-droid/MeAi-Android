package com.montecarlo.meai

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «الخدمة الدائمة»: تكنو (HiOS) بيوقف شغل التطبيقات بالخلفية، فـ WorkManager ما كان يشتغل إلا لما Moe يفتح التطبيق.
 * الحل من جزئين:
 * 1) KeeperService: خدمة أمامية مع إشعار صغير ثابت، بتخلّي النظام ما يقتل التطبيق.
 * 2) KeeperTick: منبّه كل 30 دقيقة (بيشتغل حتى والموبايل نايم) بيقرأ مستقل وخمسات وLinkedIn وReddit
 *    وبيبعت إشعار الفرص. المنبّه بيصحّي التطبيق حتى لو انقتل، وبيرجّع يشغّل الخدمة.
 * KeeperBoot: بعد إعادة تشغيل الموبايل أو تحديث التطبيق بيرجّع كل إشي يشتغل لحاله.
 */
object Keeper {
    private const val CH = "meai_keeper"
    const val NOTE_ID = 77
    private const val EVERY_MS = 30 * 60 * 1000L

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        schedule(c, 60 * 1000L, "start") // أول فحص بعد دقيقة
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, KeeperService::class.java))
            else c.startService(Intent(c, KeeperService::class.java))
        }
    }

    fun schedule(ctx: Context, delay: Long = EVERY_MS, src: String = "alarm") {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 770, Intent(ctx, KeeperTick::class.java).putExtra("src", src),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // AllowWhileIdle: بيشتغل حتى بوضع النوم (Doze)، وما بيحتاج صلاحية المنبّه الدقيق
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi)
    }

    /** سجل نبض: كل فحص بيسجّل وقته ومصدره (منبّه بالخلفية، فتح التطبيق...) والإشعارات اللي طلعت، عشان نعرف إذا الخلفية شغّالة */
    fun beat(ctx: Context, src: String) {
        val now = System.currentTimeMillis() / 1000
        val idle = runCatching { ctx.getSystemService(PowerManager::class.java).isDeviceIdleMode }.getOrDefault(false)
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
        val row = JSONObject().put("src", src).put("at", now).put("posted", Notify.drain()).put("idle", idle).put("v", v)
        // السجل محفوظ على الموبايل (آخر 40 فحص)، وبينرفع كامل جوا mostaqlStatus (مسار مسموح بقواعد Firebase)
        val prefs = Repo.prefs(ctx)
        val arr = runCatching { org.json.JSONArray(prefs.getString("keeperLog", "[]")) }.getOrDefault(org.json.JSONArray())
        arr.put(row)
        val keep = org.json.JSONArray()
        for (i in maxOf(0, arr.length() - 40) until arr.length()) keep.put(arr.get(i))
        prefs.edit().putString("keeperLog", keep.toString()).apply()
        runCatching {
            val c = URL("https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/mostaqlStatus/keeper.json").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.requestMethod = "PUT"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(keep.toString().toByteArray()) }
            c.responseCode
            c.disconnect()
        }
    }

    fun note(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, Lang.t(ctx, "Me.ai شغّال بالخلفية", "Me.ai running in background"), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val pi = PendingIntent.getActivity(
            ctx, NOTE_ID, Intent(ctx, JobsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(Lang.t(ctx, "Me.ai بيدوّر على فرص", "Me.ai is watching for leads"))
            .setContentText(Lang.t(ctx, "بيفحص مستقل وخمسات وFreelancer وLinkedIn كل 30 دقيقة", "Checks Mostaql, Khamsat, Freelancer and LinkedIn every 30 minutes"))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

class KeeperService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Keeper.note(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Keeper.NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Keeper.NOTE_ID, n)
        }
        return START_STICKY // إذا النظام سكّرها، بيرجّع يشغّلها
    }
}

class KeeperTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val c = ctx.applicationContext
        val src = intent?.getStringExtra("src") ?: "alarm"
        Keeper.schedule(c) // الفحص الجاي بعد 30 دقيقة، قبل أي إشي
        // إذا النظام قتل الخدمة، منحاول نرجّعها (ممكن يرفض وهو بالخلفية، والمنبّه بيضل شغّال بكل الأحوال)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, KeeperService::class.java))
        }
        // الفحص ممكن ياخد أكثر من دقيقة، فما منربطه بالـ receiver (عنده حد وقت). الخدمة الأمامية بتخلّي التطبيق عايش لحد ما يخلص
        val wl = c.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meai:tick")
        wl.acquire(4 * 60 * 1000L) // الموبايل بيضل صاحي لحد ما يخلص الفحص (4 دقايق كحد أقصى)
        Thread {
            try {
                runCatching { JobsWorker.check(c, src) }
            } finally {
                if (wl.isHeld) runCatching { wl.release() }
            }
        }.start()
    }
}

class KeeperBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        Keeper.start(ctx)
    }
}
