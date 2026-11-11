package io.embrace.android.embracesdk.internal.otel.logs

import io.embrace.android.embracesdk.internal.arch.schema.EmbType
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.model.ReadWriteLogRecord
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord

private val processTerminatingCrashTypes = setOf(
    EmbType.System.Crash.value,
    EmbType.System.ReactNativeCrash.value,
)

/**
 * True for JVM and React Native crash logs, which are captured as the process dies.
 */
fun ReadableLogRecord.isProcessTerminatingCrash(): Boolean = (attributes["emb.type"] as? String) in processTerminatingCrashTypes

/**
 * Passes only the logs accepted by [filter] to an external [delegate] processor in the live pipeline.
 */
internal class LiveExportFilteringLogRecordProcessor(
    private val delegate: LogRecordProcessor,
    private val filter: (ReadableLogRecord) -> Boolean,
) : LogRecordProcessor {

    override fun onEmit(log: ReadWriteLogRecord, context: Context) {
        if (filter(log)) {
            delegate.onEmit(log, context)
        }
    }

    override suspend fun forceFlush(): OperationResultCode = delegate.forceFlush()
    override suspend fun shutdown(): OperationResultCode = delegate.shutdown()
}
