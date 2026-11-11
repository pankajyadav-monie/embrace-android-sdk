package io.embrace.android.embracesdk.internal.otel.sdk

import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.tracing.Tracer

/**
 * A tracer that only feeds externally registered span processors and exporters, together with the root context
 * that replayed spans must start from so they never pick up a span that is active in the current process.
 */
class ExternalReplayTracer(
    val tracer: Tracer,
    val rootContext: Context,
    private val flushAction: () -> Boolean,
) {

    /**
     * Flushes every external processor and reports whether all spans ended since the last flush were exported.
     * Callers must only treat replayed telemetry as delivered when this returns true.
     */
    fun flush(): Boolean = flushAction()
}
