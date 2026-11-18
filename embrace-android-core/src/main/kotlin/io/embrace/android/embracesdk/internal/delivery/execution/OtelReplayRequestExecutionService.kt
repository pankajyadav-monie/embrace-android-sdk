package io.embrace.android.embracesdk.internal.delivery.execution

import io.embrace.android.embracesdk.internal.arch.schema.EmbType
import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.embrace.android.embracesdk.internal.delivery.SupportedEnvelopeType
import io.embrace.android.embracesdk.internal.logging.InternalErrorType
import io.embrace.android.embracesdk.internal.logging.InternalLogger
import io.embrace.android.embracesdk.internal.otel.ReplayExport
import io.embrace.android.embracesdk.internal.otel.sdk.ExternalReplayLogger
import io.embrace.android.embracesdk.internal.otel.sdk.ExternalReplayTracer
import io.embrace.android.embracesdk.internal.otel.sdk.findAttributeValue
import io.embrace.android.embracesdk.internal.payload.Envelope
import io.embrace.android.embracesdk.internal.payload.Log
import io.embrace.android.embracesdk.internal.payload.LogPayload
import io.embrace.android.embracesdk.internal.payload.SessionPartPayload
import io.embrace.android.embracesdk.internal.payload.Span
import io.embrace.android.embracesdk.internal.serialization.PlatformSerializer
import io.embrace.android.embracesdk.internal.session.getSessionId
import io.embrace.android.embracesdk.semconv.EmbSessionAttributes
import io.opentelemetry.kotlin.logging.Logger
import io.opentelemetry.kotlin.logging.SeverityNumber
import io.opentelemetry.kotlin.semconv.SessionAttributes
import io.opentelemetry.kotlin.tracing.SpanKind
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * A [RequestExecutionService] for when the SDK only exports via OpenTelemetry (no app ID).
 *
 * Instead of sending payloads to the Embrace backend, it replays persisted telemetry that never reached the
 * externally registered OTel processors and exporters because the process died first:
 *
 * - crash logs from CRASH envelopes, through [replayLoggerProvider];
 * - thread blockages (ANRs) from SESSION envelopes, through [replayTracerProvider]. These exist when a session was
 *   cached to disk and its process was killed (e.g. an ANR dialog's "Close app", or a swipe from recents) before
 *   the session could end and export them live.
 *
 * Everything else is reported as delivered without being replayed: in OTel-only mode it was already exported live,
 * so replaying it would duplicate it.
 *
 * A payload is only reported as delivered once the replay pipeline has been flushed and every exporter succeeded.
 * Otherwise it is kept and retried, so a crash is not lost when the device is offline on the next launch.
 *
 * Crashes may be delivered twice. External exporters are called synchronously on the crashing thread, so a fast,
 * synchronous exporter can deliver the crash live before the process dies, and it is replayed again here. Replayed
 * telemetry carries [REPLAYED_ATTRIBUTE] so backends can deduplicate on `log.record.uid`. A retry after a partial
 * failure also re-sends to exporters that already succeeded.
 *
 * A JVM crash ends its session live, which exports the session's thread blockages before the process dies. The
 * session's last periodic cache can still be resurrected as a second SESSION envelope whose blockages lack the
 * sequence id, so blockages from sessions that ended in a replayed JVM crash are not replayed. This relies on CRASH
 * envelopes being delivered before SESSION envelopes, which the delivery priority order guarantees.
 */
class OtelReplayRequestExecutionService(
    private val replayLoggerProvider: () -> ExternalReplayLogger?,
    private val serializer: PlatformSerializer,
    private val logger: InternalLogger,
    private val replayTracerProvider: () -> ExternalReplayTracer? = { null },
) : RequestExecutionService {

    /** Sessions that ended in a JVM crash, whose thread blockages were exported live when the session ended. */
    private val sessionsEndedByJvmCrash: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun attemptHttpRequest(
        payloadStream: () -> InputStream,
        envelopeType: SupportedEnvelopeType,
        payloadType: String,
    ): ExecutionResult {
        return when (envelopeType) {
            SupportedEnvelopeType.CRASH -> replayCrashes(payloadStream)
            SupportedEnvelopeType.SESSION -> replayThreadBlockages(payloadStream)
            else -> ExecutionResult.Success
        }
    }

    private fun replayCrashes(payloadStream: () -> InputStream): ExecutionResult {
        val replayLogger = replayLoggerProvider() ?: return ExecutionResult.Success
        return readAndReplay(flush = replayLogger::flush) {
            val envelope: Envelope<LogPayload> = GZIPInputStream(payloadStream()).use {
                serializer.fromJson(it, Envelope.logEnvelopeType)
            }
            val logs = envelope.data.logs?.filter(::isReplayable).orEmpty()
            logs.mapNotNullTo(sessionsEndedByJvmCrash) { it.attributes?.findAttributeValue(SESSION_ID_ATTRIBUTE) }
            logs.forEach { replay(replayLogger.logger, it) }
            logs.isNotEmpty()
        }
    }

    private fun replayThreadBlockages(payloadStream: () -> InputStream): ExecutionResult {
        val replayTracer = replayTracerProvider() ?: return ExecutionResult.Success
        return readAndReplay(flush = replayTracer::flush) {
            val envelope: Envelope<SessionPartPayload> = GZIPInputStream(payloadStream()).use {
                serializer.fromJson(it, Envelope.sessionEnvelopeType)
            }
            val sessionId = envelope.getSessionId()
            if (sessionId != null && sessionId in sessionsEndedByJvmCrash) {
                return@readAndReplay false
            }
            val blockages = ((envelope.data.spans ?: emptyList()) + (envelope.data.spanSnapshots ?: emptyList()))
                .filter(::isUnexportedThreadBlockage)
                .distinctBy { it.spanId }
            blockages.forEach { replay(replayTracer, it, sessionId) }
            blockages.isNotEmpty()
        }
    }

    /**
     * Runs [replay], which returns whether it replayed anything, then flushes the pipeline. An unreadable payload is
     * not retried because it will not become readable; a failed export is, because the exporter may recover.
     *
     * Both run inside [ReplayExport.run], so Java-backed exporters wait for and report their real result.
     */
    private fun readAndReplay(flush: () -> Boolean, replay: () -> Boolean): ExecutionResult = ReplayExport.run {
        val replayed = try {
            replay()
        } catch (exc: Throwable) {
            logger.trackInternalError(InternalErrorType.PayloadDeliveryFail, exc)
            return@run ExecutionResult.Incomplete(exc, retry = false)
        }
        when {
            !replayed || flush() -> ExecutionResult.Success
            else -> ExecutionResult.Incomplete(IllegalStateException("Replay to external OTel exporters failed"), retry = true)
        }
    }

    private fun isReplayable(log: Log): Boolean {
        val attrs = log.attributes ?: return false
        if (attrs.findAttributeValue(PrivateSpan.key) != null) {
            return false
        }
        return attrs.findAttributeValue(EMB_TYPE) in crashTypes
    }

    /**
     * Thread blockages recorded when a session ends go through the OTel span pipeline, which exports them live and
     * stamps [SEQUENCE_ID_ATTRIBUTE] on them. Blockages taken from a cached session snapshot never went through the
     * pipeline, so they lack that attribute: those are the only ones that still need exporting.
     */
    private fun isUnexportedThreadBlockage(span: Span): Boolean {
        val attrs = span.attributes ?: return false
        return attrs.findAttributeValue(EMB_TYPE) == EmbType.Performance.ThreadBlockage.value &&
            attrs.findAttributeValue(SEQUENCE_ID_ATTRIBUTE) == null &&
            attrs.findAttributeValue(PrivateSpan.key) == null &&
            span.name != null &&
            span.startTimeNanos != null
    }

    private fun replay(replayLogger: Logger, log: Log) {
        replayLogger.emit(
            body = log.body,
            timestamp = log.timeUnixNano,
            // Stored as the enum ordinal by LogMapper (not the OTel severity number), so read it back the same way.
            severityNumber = log.severityNumber?.let { SeverityNumber.entries.getOrNull(it) },
            severityText = log.severityText,
            attributes = {
                log.attributes?.forEach { attr ->
                    val key = attr.key
                    val value = attr.data
                    if (key != null && value != null) {
                        setStringAttribute(key, value)
                    }
                }
                setStringAttribute(REPLAYED_ATTRIBUTE, "true")
            },
        )
    }

    private fun replay(replayTracer: ExternalReplayTracer, span: Span, sessionId: String?) {
        val startTimeNanos = checkNotNull(span.startTimeNanos)
        val otelSpan = replayTracer.tracer.startSpan(
            name = checkNotNull(span.name),
            parentContext = replayTracer.rootContext,
            spanKind = SpanKind.INTERNAL,
            startTimestamp = startTimeNanos,
        )
        span.attributes?.forEach { attr ->
            val key = attr.key
            val value = attr.data
            if (key != null && value != null) {
                otelSpan.setStringAttribute(key, value)
            }
        }
        if (sessionId != null && span.attributes?.findAttributeValue(SESSION_ID_ATTRIBUTE) == null) {
            otelSpan.setStringAttribute(SESSION_ID_ATTRIBUTE, sessionId)
        }
        otelSpan.setStringAttribute(REPLAYED_ATTRIBUTE, "true")
        span.events?.forEach { event ->
            val name = event.name ?: return@forEach
            otelSpan.addEvent(name, event.timestampNanos) {
                event.attributes?.forEach { attr ->
                    val key = attr.key
                    val value = attr.data
                    if (key != null && value != null) {
                        setStringAttribute(key, value)
                    }
                }
            }
        }
        otelSpan.end(span.endTimeNanos ?: startTimeNanos)
    }

    private companion object {
        const val EMB_TYPE = "emb.type"
        const val SESSION_ID_ATTRIBUTE = SessionAttributes.SESSION_ID
        const val SEQUENCE_ID_ATTRIBUTE = EmbSessionAttributes.EMB_PRIVATE_SEQUENCE_ID

        /** Marks telemetry that was persisted by a previous process and exported on a later launch. */
        const val REPLAYED_ATTRIBUTE = "emb.delivery.replayed"

        /**
         * Crashes captured while the process is dying, when live export may not complete. Native crashes are not
         * included: they are emitted on the next launch while the process is healthy, so live export delivers them.
         */
        val crashTypes = setOf(
            EmbType.System.Crash.value,
            EmbType.System.ReactNativeCrash.value,
        )
    }
}
