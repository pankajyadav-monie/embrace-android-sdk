package io.embrace.android.embracesdk.internal.delivery.execution

import io.embrace.android.embracesdk.fakes.FakeClock
import io.embrace.android.embracesdk.fakes.FakeInternalLogger
import io.embrace.android.embracesdk.fakes.FakeLogRecordExporter
import io.embrace.android.embracesdk.fakes.FakeLogRecordProcessor
import io.embrace.android.embracesdk.fakes.FakeOtelKotlinClock
import io.embrace.android.embracesdk.fakes.FakeSpanExporter
import io.embrace.android.embracesdk.fakes.FakeSpanService
import io.embrace.android.embracesdk.internal.SystemInfo
import io.embrace.android.embracesdk.internal.delivery.SupportedEnvelopeType
import io.embrace.android.embracesdk.internal.otel.ReplayExport
import io.embrace.android.embracesdk.internal.otel.config.OtelSdkConfig
import io.embrace.android.embracesdk.internal.otel.logs.EventServiceImpl
import io.embrace.android.embracesdk.internal.otel.logs.LogSinkImpl
import io.embrace.android.embracesdk.internal.otel.sdk.OtelSdkWrapper
import io.embrace.android.embracesdk.internal.otel.spans.SpanSinkImpl
import io.embrace.android.embracesdk.internal.payload.Attribute
import io.embrace.android.embracesdk.internal.payload.Envelope
import io.embrace.android.embracesdk.internal.payload.Log
import io.embrace.android.embracesdk.internal.payload.LogPayload
import io.embrace.android.embracesdk.internal.payload.SessionPartPayload
import io.embrace.android.embracesdk.internal.payload.Span
import io.embrace.android.embracesdk.internal.payload.SpanEvent
import io.embrace.android.embracesdk.internal.serialization.EmbraceSerializer
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.logging.export.LogRecordExporter
import io.opentelemetry.kotlin.logging.model.ReadableLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private const val SESSION_ID = "EB4952FBA6464E09A9CDD791A8BC02DE"

internal class OtelReplayRequestExecutionServiceTest {

    private lateinit var exporter: FakeLogRecordExporter
    private lateinit var spanExporter: FakeSpanExporter
    private lateinit var processor: FakeLogRecordProcessor
    private lateinit var logger: FakeInternalLogger
    private val replayActiveDuringExport = mutableListOf<Boolean>()
    private lateinit var service: OtelReplayRequestExecutionService
    private val serializer = EmbraceSerializer()

    private val crashLog = Log(
        timeUnixNano = 1_700_000_000_000_000_000,
        severityNumber = SeverityNumber.ERROR.ordinal,
        severityText = "ERROR",
        body = "",
        attributes = listOf(
            Attribute("emb.type", "sys.android.crash"),
            Attribute("exception.type", "java.lang.IllegalStateException"),
            Attribute("log.record.uid", "abc123"),
        ),
    )

    /** The session span of a cached session whose process was killed, as it is delivered after resurrection. */
    private val sessionSpan = Span(
        spanId = "c0ffee0000000001",
        name = "emb-session",
        startTimeNanos = 1_700_000_000_000_000_000,
        endTimeNanos = 1_700_000_060_000_000_000,
        status = Span.Status.ERROR,
        attributes = listOf(
            Attribute("emb.type", "ux.session"),
            Attribute("session.id", SESSION_ID),
            Attribute("emb.error_code", "failure"),
        ),
    )

    /** A thread blockage as the ANR snapshot mapper writes it into a cached session: no pipeline attributes. */
    private val snapshotBlockage = Span(
        traceId = "0123456789abcdef0123456789abcdef",
        spanId = "c0ffee0000000002",
        parentSpanId = "0000000000000000",
        name = "emb-thread-blockage",
        startTimeNanos = 1_700_000_010_000_000_000,
        endTimeNanos = 1_700_000_050_000_000_000,
        status = Span.Status.UNSET,
        attributes = listOf(
            Attribute("emb.type", "perf.thread_blockage"),
            Attribute("interval_code", "1"),
        ),
        events = listOf(
            SpanEvent(
                name = "perf.thread_blockage_sample",
                timestampNanos = 1_700_000_012_000_000_000,
                attributes = listOf(Attribute("frames", "main-thread-stack")),
            ),
        ),
    )

    @Before
    fun setUp() {
        exporter = FakeLogRecordExporter()
        spanExporter = FakeSpanExporter()
        processor = FakeLogRecordProcessor()
        logger = FakeInternalLogger(throwOnInternalError = false)
        val config = OtelSdkConfig(
            spanSink = SpanSinkImpl(),
            logSink = LogSinkImpl(),
            sdkName = "sdk",
            sdkVersion = "1.0",
            appVersion = "2.5.1",
            packageName = "com.test.app",
            systemInfo = SystemInfo(),
        ).apply {
            addLogExporter(exporter)
            addLogRecordProcessor(processor)
            addLogExporter(object : LogRecordExporter {
                override suspend fun export(telemetry: List<ReadableLogRecord>): OperationResultCode {
                    replayActiveDuringExport += ReplayExport.isActive
                    return OperationResultCode.Success
                }

                override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
                override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
            })
            addSpanExporter(spanExporter)
        }
        lateinit var wrapper: OtelSdkWrapper
        wrapper = OtelSdkWrapper(
            otelClock = FakeOtelKotlinClock(FakeClock()),
            configuration = config,
            spanService = FakeSpanService(),
            eventService = EventServiceImpl { wrapper.sdkLogger },
            useKotlinSdk = false,
        )
        service = OtelReplayRequestExecutionService(
            replayLoggerProvider = { wrapper.externalReplayLogger },
            serializer = serializer,
            logger = logger,
            replayTracerProvider = { wrapper.externalReplayTracer },
        )
    }

    @Test
    fun `persisted crash is replayed to the external exporters with its original data`() {
        val result = deliver(SupportedEnvelopeType.CRASH, crashLog)

        assertEquals(ExecutionResult.Success, result)
        val replayed = exporter.exportedLogs.single()
        assertEquals(crashLog.timeUnixNano, replayed.timestamp)
        assertEquals(SeverityNumber.ERROR, replayed.severityNumber)
        assertEquals("sys.android.crash", replayed.attributes["emb.type"])
        assertEquals("java.lang.IllegalStateException", replayed.attributes["exception.type"])
        assertEquals("abc123", replayed.attributes["log.record.uid"])
        assertEquals("true", replayed.attributes["emb.delivery.replayed"])
    }

    @Test
    fun `non-crash envelopes are not replayed because they were already exported live`() {
        val log = crashLog.copy(attributes = listOf(Attribute("emb.type", "sys.log")))

        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.LOG, log))
        assertTrue(exporter.exportedLogs.isEmpty())
    }

    @Test
    fun `thread blockage from a killed session is replayed with its original data`() {
        val result = deliverSession(SessionPartPayload(spans = listOf(sessionSpan, snapshotBlockage)))

        assertEquals(ExecutionResult.Success, result)
        val replayed = spanExporter.exportedSpans.single()
        assertEquals("emb-thread-blockage", replayed.name)
        assertEquals(snapshotBlockage.startTimeNanos, replayed.startTimestamp)
        assertEquals(snapshotBlockage.endTimeNanos, replayed.endTimestamp)
        assertEquals("perf.thread_blockage", replayed.attributes["emb.type"])
        assertEquals("1", replayed.attributes["interval_code"])
        assertEquals(SESSION_ID, replayed.attributes["session.id"])
        assertEquals("true", replayed.attributes["emb.delivery.replayed"])
        assertNull(replayed.attributes["emb.private.sequence_id"])
        val sample = replayed.events.single()
        assertEquals("perf.thread_blockage_sample", sample.name)
        assertEquals(snapshotBlockage.events?.single()?.timestampNanos, sample.timestamp)
        assertEquals("main-thread-stack", sample.attributes["frames"])
    }

    @Test
    fun `thread blockage still in the span snapshots is replayed once`() {
        val payload = SessionPartPayload(
            spans = listOf(snapshotBlockage),
            spanSnapshots = listOf(sessionSpan, snapshotBlockage),
        )

        assertEquals(ExecutionResult.Success, deliverSession(payload))
        assertEquals(1, spanExporter.exportedSpans.size)
    }

    @Test
    fun `thread blockage already exported live is not replayed`() {
        val liveBlockage = snapshotBlockage.copy(
            attributes = snapshotBlockage.attributes.orEmpty() + Attribute("emb.private.sequence_id", "42"),
        )
        val networkSpan = Span(
            spanId = "c0ffee0000000003",
            name = "emb-GET /api",
            startTimeNanos = 1L,
            endTimeNanos = 2L,
            attributes = listOf(Attribute("emb.type", "perf.network_request")),
        )

        val result = deliverSession(SessionPartPayload(spans = listOf(sessionSpan, liveBlockage, networkSpan)))

        assertEquals(ExecutionResult.Success, result)
        assertTrue(spanExporter.exportedSpans.isEmpty())
    }

    @Test
    fun `private thread blockage is not replayed`() {
        val privateBlockage = snapshotBlockage.copy(
            attributes = snapshotBlockage.attributes.orEmpty() + Attribute("emb.private", "true"),
        )

        assertEquals(ExecutionResult.Success, deliverSession(SessionPartPayload(spans = listOf(privateBlockage))))
        assertTrue(spanExporter.exportedSpans.isEmpty())
    }

    @Test
    fun `unreadable session payload is reported and not retried`() {
        val result = service.attemptHttpRequest(
            payloadStream = { ByteArrayInputStream("not gzip".toByteArray()) },
            envelopeType = SupportedEnvelopeType.SESSION,
            payloadType = "session",
        )

        assertTrue(result is ExecutionResult.Incomplete)
        assertFalse(result.shouldRetry)
        assertTrue(spanExporter.exportedSpans.isEmpty())
    }

    @Test
    fun `native crashes are not replayed because they are exported live on the next launch`() {
        val nativeCrash = crashLog.copy(attributes = listOf(Attribute("emb.type", "sys.android.native_crash")))

        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, nativeCrash))
        assertTrue(exporter.exportedLogs.isEmpty())
    }

    @Test
    fun `private logs in a crash envelope are not replayed`() {
        val privateLog = crashLog.copy(attributes = crashLog.attributes.orEmpty() + Attribute("emb.private", "true"))

        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, privateLog))
        assertTrue(exporter.exportedLogs.isEmpty())
    }

    @Test
    fun `unreadable payload is reported and not retried`() {
        val result = service.attemptHttpRequest(
            payloadStream = { ByteArrayInputStream("not gzip".toByteArray()) },
            envelopeType = SupportedEnvelopeType.CRASH,
            payloadType = "crash",
        )

        assertTrue(result is ExecutionResult.Incomplete)
        assertFalse(result.shouldRetry)
        assertEquals(1, logger.internalErrorMessages.size)
    }

    @Test
    fun `crash is kept for retry when an exporter fails`() {
        exporter.exportResult = OperationResultCode.Failure

        val result = deliver(SupportedEnvelopeType.CRASH, crashLog)

        assertTrue(result is ExecutionResult.Incomplete)
        assertTrue(result.shouldRetry)
        assertEquals(1, exporter.exportedLogs.size)
    }

    @Test
    fun `crash is delivered on retry once the exporter recovers`() {
        exporter.exportResult = OperationResultCode.Failure
        assertTrue(deliver(SupportedEnvelopeType.CRASH, crashLog).shouldRetry)

        exporter.exportResult = OperationResultCode.Success
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, crashLog))
    }

    @Test
    fun `external processors are flushed before a crash is reported as delivered`() {
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, crashLog))

        assertEquals(listOf(""), processor.processedLogBodies)
        assertEquals(1, processor.forceFlushCount)
    }

    @Test
    fun `crash is kept for retry when an external processor fails to flush`() {
        processor.forceFlushResult = OperationResultCode.Failure

        assertTrue(deliver(SupportedEnvelopeType.CRASH, crashLog).shouldRetry)
    }

    @Test
    fun `nothing is flushed when a crash envelope has nothing to replay`() {
        val log = crashLog.copy(attributes = listOf(Attribute("emb.type", "sys.log")))

        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, log))
        assertEquals(0, processor.forceFlushCount)
    }

    @Test
    fun `thread blockage is kept for retry when a span exporter fails`() {
        spanExporter.exportResult = OperationResultCode.Failure

        val result = deliverSession(SessionPartPayload(spans = listOf(sessionSpan, snapshotBlockage)))

        assertTrue(result is ExecutionResult.Incomplete)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun `exports during a replay run with the replay flag set so java exporters report their real result`() {
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, crashLog))

        assertEquals(listOf(true), replayActiveDuringExport)
        assertFalse(ReplayExport.isActive)
    }

    @Test
    fun `thread blockages of a session that ended in a jvm crash are not replayed`() {
        val crashInSession = crashLog.copy(attributes = crashLog.attributes.orEmpty() + Attribute("session.id", SESSION_ID))
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, crashInSession))

        val result = deliverSession(SessionPartPayload(spans = listOf(sessionSpan, snapshotBlockage)))

        assertEquals(ExecutionResult.Success, result)
        assertTrue(spanExporter.exportedSpans.isEmpty())
    }

    @Test
    fun `thread blockages of other sessions are still replayed after a jvm crash`() {
        val crashInOtherSession = crashLog.copy(attributes = crashLog.attributes.orEmpty() + Attribute("session.id", "OTHER"))
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.CRASH, crashInOtherSession))

        assertEquals(ExecutionResult.Success, deliverSession(SessionPartPayload(spans = listOf(sessionSpan, snapshotBlockage))))
        assertEquals(1, spanExporter.exportedSpans.size)
    }

    private fun deliverSession(payload: SessionPartPayload): ExecutionResult {
        val bytes = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { gzip ->
                serializer.toJson(Envelope(data = payload), Envelope.sessionEnvelopeType, gzip)
            }
            out.toByteArray()
        }
        return service.attemptHttpRequest(
            payloadStream = { ByteArrayInputStream(bytes) },
            envelopeType = SupportedEnvelopeType.SESSION,
            payloadType = "session",
        )
    }

    private fun deliver(type: SupportedEnvelopeType, log: Log): ExecutionResult {
        val bytes = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { gzip ->
                serializer.toJson(Envelope(data = LogPayload(logs = listOf(log))), Envelope.logEnvelopeType, gzip)
            }
            out.toByteArray()
        }
        return service.attemptHttpRequest(
            payloadStream = { ByteArrayInputStream(bytes) },
            envelopeType = type,
            payloadType = "crash",
        )
    }
}
