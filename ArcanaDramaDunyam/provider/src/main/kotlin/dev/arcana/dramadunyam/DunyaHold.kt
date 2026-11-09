package dev.arcana.dramadunyam

import android.content.SharedPreferences

/** Both browser and HTTP responses extend the same source hold through one lock. */
internal object DunyaHold {
    private val lock = Any()
    fun remember(store: SharedPreferences, header: String?) = synchronized(lock) {
        val until = maxOf(store.getLong("retry_until_ms", 0L), DunyaContract.retryUntil(header, System.currentTimeMillis()))
        store.edit().putLong("retry_until_ms", until).apply()
    }
}
