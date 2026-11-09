package dev.arcana.lidershort

import android.content.SharedPreferences

/** Both browser and HTTP responses extend the same source hold through one lock. */
internal object LiderShortHold {
    private val lock = Any()
    fun remember(store: SharedPreferences, header: String?) = synchronized(lock) {
        val until = maxOf(store.getLong("retry_until_ms", 0L), LiderShortContract.retryUntil(header, System.currentTimeMillis()))
        store.edit().putLong("retry_until_ms", until).apply()
    }
}
