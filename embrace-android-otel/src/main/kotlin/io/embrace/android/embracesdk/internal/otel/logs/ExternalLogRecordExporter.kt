package io.embrace.android.embracesdk.internal.otel.logs

import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.embrace.android.embracesdk.internal.otel.exportToAll
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.model.ReadWriteLogRecord
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports log records only to externally registered exporters, skipping Embrace's [LogSink].
 *
 * Private records are filtered out, matching what [DefaultLogRecordExporter] forwards to external exporters.
 */
internal class ExternalLogRecordExporter(
    private val externalExporters: List<LogRecordExporter>,
) : LogRecordExporter {

    override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode {
        val exportable = telemetry.filterNot { it.attributes.containsKey(PrivateSpan.key) }
        return externalExporters.exportToAll(exportable) { export(it) }
    }

    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}

/**
 * Exports each log record synchronously as it is emitted and remembers whether any export failed, so that
 * [forceFlush] can report it. This lets a replay find out whether its records actually reached the exporters.
 */
internal class ExportResultTrackingLogRecordProcessor(
    private val logRecordExporter: LogRecordExporter,
) : LogRecordProcessor {

    private val failed = AtomicBoolean(false)

    override fun onEmit(log: ReadWriteLogRecord, context: Context) {
        val result = runCatching { runBlocking { logRecordExporter.export(listOf(log)) } }.getOrNull()
        if (result != OperationResultCode.Success) {
            failed.set(true)
        }
    }

    /** Fails if any export since the previous flush failed, then resets. */
    override suspend fun forceFlush(): OperationResultCode = when {
        failed.getAndSet(false) -> OperationResultCode.Failure
        else -> OperationResultCode.Success
    }

    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
