package io.embrace.android.embracesdk.otel.java

import io.embrace.android.embracesdk.internal.otel.ReplayExport
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.TimeUnit

/**
 * How long a replay waits for a Java exporter or processor to finish before treating it as failed.
 */
internal const val REPLAY_RESULT_TIMEOUT_SECONDS = 30L

/**
 * During a replay ([ReplayExport.isActive]), waits for this result and returns the shared success or failure instance,
 * which the Kotlin compat adapters map correctly. Otherwise returns this result unchanged, without blocking.
 */
internal fun CompletableResultCode.awaitedDuringReplay(): CompletableResultCode {
    if (!ReplayExport.isActive) {
        return this
    }
    join(REPLAY_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return when {
        isSuccess -> CompletableResultCode.ofSuccess()
        else -> CompletableResultCode.ofFailure()
    }
}

internal class ReplayAwareSpanExporter(
    private val delegate: SpanExporter,
) : SpanExporter {
    override fun export(
        spans: Collection<SpanData>
    ): CompletableResultCode = delegate.export(spans).awaitedDuringReplay()
    override fun flush(): CompletableResultCode = delegate.flush().awaitedDuringReplay()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
    override fun close() = delegate.close()
    override fun toString(): String = delegate.toString()
}

internal class ReplayAwareLogRecordExporter(
    private val delegate: LogRecordExporter,
) : LogRecordExporter {
    override fun export(
        logs: Collection<LogRecordData>
    ): CompletableResultCode = delegate.export(logs).awaitedDuringReplay()
    override fun flush(): CompletableResultCode = delegate.flush().awaitedDuringReplay()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
    override fun close() = delegate.close()
    override fun toString(): String = delegate.toString()
}

/**
 * Note: a batching processor such as OTel Java's `BatchSpanProcessor` completes [forceFlush] once its queue is drained,
 * even if the export failed, so a failed replay through it is not detected. Register an exporter to get retries.
 */
internal class ReplayAwareSpanProcessor(
    private val delegate: SpanProcessor,
) : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) = delegate.onStart(parentContext, span)
    override fun isStartRequired(): Boolean = delegate.isStartRequired
    override fun onEnd(span: ReadableSpan) = delegate.onEnd(span)
    override fun isEndRequired(): Boolean = delegate.isEndRequired
    override fun forceFlush(): CompletableResultCode = delegate.forceFlush().awaitedDuringReplay()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
    override fun close() = delegate.close()
    override fun toString(): String = delegate.toString()
}

/**
 * Note: a batching processor such as OTel Java's `BatchLogRecordProcessor` completes [forceFlush] once its queue is
 * drained, even if the export failed, so a failed replay through it is not detected. Register an exporter to get retries.
 */
internal class ReplayAwareLogRecordProcessor(
    private val delegate: LogRecordProcessor,
) : LogRecordProcessor {
    override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) = delegate.onEmit(context, logRecord)
    override fun forceFlush(): CompletableResultCode = delegate.forceFlush().awaitedDuringReplay()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
    override fun close() = delegate.close()
    override fun toString(): String = delegate.toString()
}
