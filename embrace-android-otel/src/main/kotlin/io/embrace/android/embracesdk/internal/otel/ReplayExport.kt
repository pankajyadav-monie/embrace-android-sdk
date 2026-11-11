package io.embrace.android.embracesdk.internal.otel

/**
 * Marks the current thread as replaying persisted telemetry to external OTel exporters.
 *
 * Exporters and processors adapted from OpenTelemetry Java return a [io.opentelemetry.sdk.common.CompletableResultCode]
 * that usually completes later, and the Kotlin compat adapters report anything other than the shared success instance
 * as a failure. Java-backed exporters check [isActive] to wait for the real result during a replay, so the replay knows
 * whether the telemetry was delivered. Live exports are not affected and stay non-blocking.
 */
object ReplayExport {

    private val active = ThreadLocal<Boolean>()

    /**
     * True while [run] is executing on the current thread.
     */
    val isActive: Boolean
        get() = active.get() == true

    /**
     * Runs [block] with [isActive] set on the current thread.
     */
    fun <T> run(block: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return block()
        } finally {
            if (previous == null) {
                active.remove()
            } else {
                active.set(previous)
            }
        }
    }
}
