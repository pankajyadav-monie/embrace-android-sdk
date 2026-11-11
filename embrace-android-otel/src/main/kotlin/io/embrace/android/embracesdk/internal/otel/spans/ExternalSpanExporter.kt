package io.embrace.android.embracesdk.internal.otel.spans

import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.model.ReadWriteSpan
import io.opentelemetry.kotlin.tracing.model.ReadableSpan
import kotlinx.coroutines.runBlocking

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
        if (exportable.isEmpty()) {
            return OperationResultCode.Success
        }
        var result: OperationResultCode = OperationResultCode.Success
        externalExporters.forEach { exporter ->
            try {
                if (exporter.export(exportable) != OperationResultCode.Success) {
                    result = OperationResultCode.Failure
                }
            } catch (ignored: Throwable) {
                result = OperationResultCode.Failure
            }
        }
        return result
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}

/**
 * Exports each span as soon as it ends. Unlike [EmbraceSpanProcessor] it adds no attributes, so replayed spans keep
 * the session and process they were recorded in rather than the current ones.
 */
internal class ExportOnEndSpanProcessor(
    private val spanExporter: SpanExporter,
) : SpanProcessor {

    override fun onStart(span: ReadWriteSpan, parentContext: Context) {
    }

    override fun onEnding(span: ReadWriteSpan) {
    }

    override fun onEnd(span: ReadableSpan) {
        runBlocking { spanExporter.export(listOf(span)) }
    }

    override fun isStartRequired(): Boolean = false
    override fun isEndRequired(): Boolean = true
    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
