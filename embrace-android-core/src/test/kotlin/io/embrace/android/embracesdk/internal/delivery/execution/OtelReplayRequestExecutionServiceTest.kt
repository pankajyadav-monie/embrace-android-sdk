package io.embrace.android.embracesdk.internal.delivery.execution

import io.embrace.android.embracesdk.fakes.FakeClock
import io.embrace.android.embracesdk.fakes.FakeInternalLogger
import io.embrace.android.embracesdk.fakes.FakeLogRecordExporter
import io.embrace.android.embracesdk.fakes.FakeOtelKotlinClock
import io.embrace.android.embracesdk.fakes.FakeSpanService
import io.embrace.android.embracesdk.internal.SystemInfo
import io.embrace.android.embracesdk.internal.delivery.SupportedEnvelopeType
import io.embrace.android.embracesdk.internal.otel.config.OtelSdkConfig
import io.embrace.android.embracesdk.internal.otel.logs.EventServiceImpl
import io.embrace.android.embracesdk.internal.otel.logs.LogSinkImpl
import io.embrace.android.embracesdk.internal.otel.sdk.OtelSdkWrapper
import io.embrace.android.embracesdk.internal.otel.spans.SpanSinkImpl
import io.embrace.android.embracesdk.internal.payload.Attribute
import io.embrace.android.embracesdk.internal.payload.Envelope
import io.embrace.android.embracesdk.internal.payload.Log
import io.embrace.android.embracesdk.internal.payload.LogPayload
import io.embrace.android.embracesdk.internal.serialization.EmbraceSerializer
import io.opentelemetry.kotlin.logging.SeverityNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal class OtelReplayRequestExecutionServiceTest {

    private lateinit var exporter: FakeLogRecordExporter
    private lateinit var logger: FakeInternalLogger
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

    @Before
    fun setUp() {
        exporter = FakeLogRecordExporter()
        logger = FakeInternalLogger(throwOnInternalError = false)
        val config = OtelSdkConfig(
            spanSink = SpanSinkImpl(),
            logSink = LogSinkImpl(),
            sdkName = "sdk",
            sdkVersion = "1.0",
            appVersion = "2.5.1",
            packageName = "com.test.app",
            systemInfo = SystemInfo(),
        ).apply { addLogExporter(exporter) }
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
        assertEquals(ExecutionResult.Success, deliver(SupportedEnvelopeType.SESSION, log))
        assertTrue(exporter.exportedLogs.isEmpty())
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
