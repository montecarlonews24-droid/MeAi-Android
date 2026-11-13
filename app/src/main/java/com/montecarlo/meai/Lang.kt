package com.montecarlo.meai

import android.content.Context

/** لغة التطبيق: الإنجليزي هو الأساس، والعربي من الزر */
object Lang {
    fun en(ctx: Context): Boolean {
        val saved = Repo.prefs(ctx).getString("lang", null)
        return saved != "ar"
    }

    fun toggle(ctx: Context) =
        Repo.prefs(ctx).edit().putString("lang", if (en(ctx)) "ar" else "en").apply()

    fun t(ctx: Context, ar: String, en: String) = if (en(ctx)) en else ar
}
