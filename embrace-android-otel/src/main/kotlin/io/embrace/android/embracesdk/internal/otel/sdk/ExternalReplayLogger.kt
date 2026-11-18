package io.embrace.android.embracesdk.internal.otel.sdk

import io.opentelemetry.kotlin.logging.Logger

/**
 * A logger that only feeds externally registered log processors and exporters.
 */
class ExternalReplayLogger(
    val logger: Logger,
    private val flushAction: () -> Boolean,
) {

    /**
     * Flushes every external processor and reports whether all records emitted since the last flush were exported.
     * Callers must only treat replayed telemetry as delivered when this returns true.
     */
    fun flush(): Boolean = flushAction()
}
