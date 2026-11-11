package io.embrace.android.embracesdk.otel.java

import io.embrace.android.embracesdk.internal.otel.ReplayExport
import io.opentelemetry.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.export.toOtelKotlinLogRecordProcessor
import io.opentelemetry.kotlin.tracing.export.toOtelKotlinSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ReplayAwareJavaExportTest {

    private val completer = Executors.newSingleThreadScheduledExecutor()

    /** Behaves like OTLP exporters: returns a new result that completes later, on another thread. */
    private inner class AsyncSpanExporter(private val succeed: Boolean) : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode = completeLater(succeed)
        override fun flush(): CompletableResultCode = completeLater(succeed)
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    /** Behaves like a batching processor: forceFlush returns a new result that completes later. */
    private inner class AsyncFlushLogRecordProcessor(private val succeed: Boolean) : LogRecordProcessor {
        override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
        }

        override fun forceFlush(): CompletableResultCode = completeLater(succeed)
    }

    private fun completeLater(succeed: Boolean): CompletableResultCode {
        val result = CompletableResultCode()
        completer.schedule({ if (succeed) result.succeed() else result.fail() }, 50, TimeUnit.MILLISECONDS)
        return result
    }

    @Test
    fun `outside a replay the delegate result is returned without waiting`() {
        val pending = CompletableResultCode()
        val exporter = ReplayAwareSpanExporter(object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode = pending
            override fun flush(): CompletableResultCode = pending
            override fun shutdown(): CompletableResultCode = pending
        })

        assertSame(pending, exporter.export(emptyList()))
        assertFalse(pending.isDone)
    }

    @Test
    fun `during a replay a successful async export is reported as success by the kotlin adapter`() {
        val exporter = ReplayAwareSpanExporter(AsyncSpanExporter(succeed = true)).toOtelKotlinSpanExporter()

        val result = ReplayExport.run { runBlocking { exporter.export(emptyList()) } }

        assertEquals(OperationResultCode.Success, result)
    }

    @Test
    fun `during a replay a failed async export is reported as failure`() {
        val exporter = ReplayAwareSpanExporter(AsyncSpanExporter(succeed = false)).toOtelKotlinSpanExporter()

        val result = ReplayExport.run { runBlocking { exporter.export(emptyList()) } }

        assertEquals(OperationResultCode.Failure, result)
    }

    @Test
    fun `without the wrapper the kotlin adapter reports a successful async export as failure`() {
        val exporter = AsyncSpanExporter(succeed = true).toOtelKotlinSpanExporter()

        val result = ReplayExport.run { runBlocking { exporter.export(emptyList()) } }

        assertEquals(OperationResultCode.Failure, result)
    }

    @Test
    fun `during a replay an async processor flush is awaited`() {
        val processor = ReplayAwareLogRecordProcessor(AsyncFlushLogRecordProcessor(succeed = true))
            .toOtelKotlinLogRecordProcessor()

        val result = ReplayExport.run { runBlocking { processor.forceFlush() } }

        assertEquals(OperationResultCode.Success, result)
    }

    @Test
    fun `replay flag is cleared after the block`() {
        ReplayExport.run { }

        assertFalse(ReplayExport.isActive)
    }
}
