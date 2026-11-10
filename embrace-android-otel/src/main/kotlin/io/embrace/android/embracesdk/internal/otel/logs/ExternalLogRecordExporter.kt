package io.embrace.android.embracesdk.internal.otel.logs

import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord

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
