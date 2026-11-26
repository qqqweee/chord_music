package com.example.chord.data.stats

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/** Three recaps. [days] is how far back the story looks. */
enum class RecapPeriod(val days: Int, val word: String, val noun: String) {
    Week(7, "WEEK", "week"),
    Month(30, "MONTH", "month"),
    Year(365, "YEAR", "year"),
}

/**
 * When a recap is "ready". One key per drop, so each drop shows once.
 *   Week : every Sunday 18:00. Stays ready 6 days.
 *   Month: last day of month 18:00. Stays ready 7 days.
 *   Year : Dec 1, 09:00. Stays ready 31 days.
 * Home teaser reads [ready]. The worker reads [dueForNotification].
 */
object RecapSchedule {
    private const val FILE = "chord_recap"
    private const val MIN_MS = 5 * 60_000L // under 5 min of music, nothing to tell

    /** Tapped notification sets this. ChordApp opens the story and clears it. */
    var launchRequest by mutableStateOf<RecapPeriod?>(null)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Most recent drop time for the period, and a stable key for it. */
    internal fun lastDrop(p: RecapPeriod, now: Calendar = Calendar.getInstance()): Pair<Long, String> {
        val c = now.clone() as Calendar
        fun Calendar.at(h: Int) { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when (p) {
            RecapPeriod.Week -> {
                c.at(18)
                while (c.after(now) || c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) c.add(Calendar.DAY_OF_YEAR, -1)
            }
            RecapPeriod.Month -> {
                c.at(18)
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
                if (c.after(now)) { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, -1); c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH)) }
            }
            RecapPeriod.Year -> {
                c.at(9)
                c.set(Calendar.MONTH, Calendar.DECEMBER); c.set(Calendar.DAY_OF_MONTH, 1)
                if (c.after(now)) c.add(Calendar.YEAR, -1)
            }
        }
        return c.timeInMillis to "${p.name}-${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun window(p: RecapPeriod) = when (p) {
        RecapPeriod.Week -> 6L; RecapPeriod.Month -> 7L; RecapPeriod.Year -> 31L
    } * 24 * 3_600_000L

    /** Drops that are live right now. */
    private fun live(now: Calendar = Calendar.getInstance()): List<Pair<RecapPeriod, String>> =
        RecapPeriod.values().mapNotNull { p ->
            val (t, key) = lastDrop(p, now)
            if (now.timeInMillis - t in 0..window(p)) p to key else null
        }

    /** Bigger story first: Year, Month, Week. Null when nothing new, or too little listening. */
    suspend fun ready(ctx: Context): RecapPeriod? {
        val seen = prefs(ctx).getStringSet("seen", emptySet()).orEmpty()
        val store = ListenStatsStore.get(ctx)
        return live().filter { it.second !in seen }.sortedByDescending { it.first.days }
            .firstOrNull { store.totalMs(it.first.days) >= MIN_MS }?.first
    }

    /** User opened or dismissed it. Hides the teaser for this drop. */
    fun markSeen(ctx: Context, p: RecapPeriod) {
        val pr = prefs(ctx)
        val key = lastDrop(p).second
        pr.edit().putStringSet("seen", pr.getStringSet("seen", emptySet()).orEmpty().toMutableSet().also { it.add(key) }.takeLast(40).toSet()).apply()
    }

    /** Drops that need a system notification now. Marks them as sent. */
    suspend fun dueForNotification(ctx: Context): List<RecapPeriod> {
        val pr = prefs(ctx)
        val sent = pr.getStringSet("sent", emptySet()).orEmpty().toMutableSet()
        val store = ListenStatsStore.get(ctx)
        val out = mutableListOf<RecapPeriod>()
        for ((p, key) in live().sortedByDescending { it.first.days }) {
            if (key in sent) continue
            sent += key // even with no data, do not ask again for this drop
            if (store.totalMs(p.days) >= MIN_MS) out += p
        }
        pr.edit().putStringSet("sent", sent.toList().takeLast(40).toSet()).apply()
        return out
    }
}

private fun <T> Set<T>.takeLast(n: Int): List<T> = toList().let { if (it.size > n) it.takeLast(n) else it }
