package com.montecarlo.meai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** تقييمات 👍/👎 — بتنحفظ بـ Firebase والـ Agent بيقرأها كل يوم */
object Feedback {
    private const val DB = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/feedback/"

    fun key(name: String) = name.replace(Regex("[.#$\\[\\]/]"), "_").trim().take(80).ifBlank { "idea" }

    /** "up" أو "down" أو null */
    fun local(ctx: Context, name: String): String? =
        Repo.prefs(ctx).getString("vote:" + key(name), null)

    /** بيرجع true إذا انحفظ على Firebase */
    fun send(ctx: Context, idea: Idea, vote: String?): Boolean {
        val k = key(idea.name)
        val c = URL("$DB$k.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        return try {
            if (vote == null) {
                c.requestMethod = "DELETE"
            } else {
                c.requestMethod = "PUT"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("name", idea.name)
                    .put("pitch", idea.pitch.take(300))
                    .put("vote", vote)
                    .put("ts", System.currentTimeMillis())
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val ok = c.responseCode in 200..299
            if (ok) {
                val e = Repo.prefs(ctx).edit()
                if (vote == null) e.remove("vote:$k") else e.putString("vote:$k", vote)
                e.apply()
            }
            ok
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }
}
