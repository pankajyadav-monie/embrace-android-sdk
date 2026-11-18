package io.embrace.android.embracesdk.internal.otel.spans

import io.embrace.android.embracesdk.fakes.FakeReadWriteSpan
import io.embrace.android.embracesdk.fakes.FakeSpanExporter
import io.embrace.android.embracesdk.semconv.EmbSessionAttributes
import io.opentelemetry.kotlin.NoopOpenTelemetry
import io.opentelemetry.kotlin.semconv.SessionAttributes
import io.opentelemetry.kotlin.semconv.UserAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbraceSpanProcessorTest {

    @Test
    fun `test export`() {
        val spanExporter = FakeSpanExporter()
        val processor = EmbraceSpanProcessor({ "sid" }, "pid", spanExporter)
        val span = FakeReadWriteSpan()
        processor.onStart(span, NoopOpenTelemetry.context.implicit())

        assertEquals(span.attributes[EmbSessionAttributes.EMB_PRIVATE_SEQUENCE_ID], "1")
        assertEquals(span.attributes[EmbSessionAttributes.EMB_PROCESS_IDENTIFIER], "pid")
        assertEquals(span.attributes[SessionAttributes.SESSION_ID], "sid")

        processor.onEnd(span)
        assertEquals(span, spanExporter.exportedSpans.single())
    }

    @Test
    fun `user id is added to every span when set`() {
        var userId: String? = "a1b2c3"
        val processor = EmbraceSpanProcessor({ "sid" }, "pid", FakeSpanExporter(), userIdProvider = { userId })

        val first = FakeReadWriteSpan()
        processor.onStart(first, NoopOpenTelemetry.context.implicit())
        assertEquals("a1b2c3", first.attributes[UserAttributes.USER_ID])

        userId = "d4e5f6"
        val second = FakeReadWriteSpan()
        processor.onStart(second, NoopOpenTelemetry.context.implicit())
        assertEquals("d4e5f6", second.attributes[UserAttributes.USER_ID])
    }

    @Test
    fun `user id is omitted when unset or blank`() {
        listOf(null, "", " ").forEach { userId ->
            val processor = EmbraceSpanProcessor({ "sid" }, "pid", FakeSpanExporter(), userIdProvider = { userId })
            val span = FakeReadWriteSpan()
            processor.onStart(span, NoopOpenTelemetry.context.implicit())
            assertFalse(span.attributes.containsKey(UserAttributes.USER_ID))
        }
    }
}
