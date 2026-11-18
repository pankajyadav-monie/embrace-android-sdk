package io.embrace.android.embracesdk.internal.otel.spans

import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.embrace.android.embracesdk.internal.otel.exportToAll
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.model.ReadWriteSpan
import io.opentelemetry.kotlin.tracing.model.ReadableSpan
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports spans only to externally registered exporters, skipping Embrace's [SpanSink].
 *
 * Private spans are filtered out, matching what [DefaultSpanExporter] forwards to external exporters.
 */
internal class ExternalSpanExporter(
    private val externalExporters: List<SpanExporter>,
) : SpanExporter {

    override suspend fun export(telemetry: List<SpanData>): OperationResultCode {
        val exportable = telemetry.filterNot { it.attributes.containsKey(PrivateSpan.key) }
        return externalExporters.exportToAll(exportable) { export(it) }
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}

/**
 * Exports each span synchronously as soon as it ends and remembers whether any export failed, so that [forceFlush]
 * can report it. Unlike [EmbraceSpanProcessor] it adds no attributes, so replayed spans keep the session and process
 * they were recorded in rather than the current ones.
 */
internal class ExportOnEndSpanProcessor(
    private val spanExporter: SpanExporter,
) : SpanProcessor {

    private val failed = AtomicBoolean(false)

    override fun onStart(span: ReadWriteSpan, parentContext: Context) {
    }

    override fun onEnding(span: ReadWriteSpan) {
    }

    override fun onEnd(span: ReadableSpan) {
        val result = runCatching { runBlocking { spanExporter.export(listOf(span)) } }.getOrNull()
        if (result != OperationResultCode.Success) {
            failed.set(true)
        }
    }

    override fun isStartRequired(): Boolean = false
    override fun isEndRequired(): Boolean = true

    /** Fails if any export since the previous flush failed, then resets. */
    override suspend fun forceFlush(): OperationResultCode = when {
        failed.getAndSet(false) -> OperationResultCode.Failure
        else -> OperationResultCode.Success
    }

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
