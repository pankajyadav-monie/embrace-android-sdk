package io.embrace.android.embracesdk.internal.otel.spans

import io.embrace.android.embracesdk.semconv.EmbSessionAttributes
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.semconv.SessionAttributes
import io.opentelemetry.kotlin.semconv.UserAttributes
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.model.ReadWriteSpan
import io.opentelemetry.kotlin.tracing.model.ReadableSpan
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

class EmbraceSpanProcessor(
    private val sessionIdProvider: () -> String?,
    private val processIdentifier: String,
    private val spanExporter: SpanExporter,
    private val userIdProvider: () -> String? = { null },
) : SpanProcessor {

    private val counter = AtomicLong(1)

    override fun onStart(span: ReadWriteSpan, parentContext: Context) {
        span.setStringAttribute(EmbSessionAttributes.EMB_PRIVATE_SEQUENCE_ID, counter.getAndIncrement().toString())
        span.setStringAttribute(EmbSessionAttributes.EMB_PROCESS_IDENTIFIER, processIdentifier)
        sessionIdProvider()?.let { sessionId ->
            span.setStringAttribute(SessionAttributes.SESSION_ID, sessionId)
        }
        // The user identifier set via Embrace.setUserIdentifier(), as OTel `user.id`. In OTel-only delivery
        // this is the only way it leaves the device (Embrace's own envelope metadata is never sent).
        userIdProvider()?.takeIf { it.isNotBlank() }?.let { userId ->
            span.setStringAttribute(UserAttributes.USER_ID, userId)
        }
    }

    override fun onEnding(span: ReadWriteSpan) {
    }

    override fun onEnd(span: ReadableSpan) {
        runBlocking { spanExporter.export(mutableListOf(span)) }
    }

    override fun isStartRequired() = true
    override fun isEndRequired() = true
    override suspend fun forceFlush(): OperationResultCode = OperationResultCode.Success
    override suspend fun shutdown(): OperationResultCode = OperationResultCode.Success
}
