package io.embrace.android.embracesdk.internal.delivery.execution

import io.embrace.android.embracesdk.internal.arch.schema.EmbType
import io.embrace.android.embracesdk.internal.arch.schema.PrivateSpan
import io.embrace.android.embracesdk.internal.delivery.SupportedEnvelopeType
import io.embrace.android.embracesdk.internal.logging.InternalErrorType
import io.embrace.android.embracesdk.internal.logging.InternalLogger
import io.embrace.android.embracesdk.internal.otel.sdk.findAttributeValue
import io.embrace.android.embracesdk.internal.payload.Envelope
import io.embrace.android.embracesdk.internal.payload.Log
import io.embrace.android.embracesdk.internal.payload.LogPayload
import io.embrace.android.embracesdk.internal.serialization.PlatformSerializer
import io.opentelemetry.kotlin.logging.Logger
import io.opentelemetry.kotlin.logging.SeverityNumber
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A [RequestExecutionService] for when the SDK only exports via OpenTelemetry (no app ID).
 *
 * Instead of sending payloads to the Embrace backend, it replays persisted crash logs through [replayLoggerProvider],
 * which only feeds the externally registered OTel log processors and exporters. This lets crashes captured just before
 * a process died reach the OTel destinations on the next launch, the same way they would reach Embrace with an app ID.
 *
 * Every other envelope type is reported as delivered without being replayed: in OTel-only mode that telemetry was
 * already exported live, so replaying it would duplicate it.
 */
class OtelReplayRequestExecutionService(
    private val replayLoggerProvider: () -> Logger?,
    private val serializer: PlatformSerializer,
    private val logger: InternalLogger,
) : RequestExecutionService {

    override fun attemptHttpRequest(
        payloadStream: () -> InputStream,
        envelopeType: SupportedEnvelopeType,
        payloadType: String,
    ): ExecutionResult {
        if (envelopeType != SupportedEnvelopeType.CRASH) {
            return ExecutionResult.Success
        }
        val replayLogger = replayLoggerProvider() ?: return ExecutionResult.Success
        return try {
            val envelope: Envelope<LogPayload> = GZIPInputStream(payloadStream()).use {
                serializer.fromJson(it, Envelope.logEnvelopeType)
            }
            envelope.data.logs
                ?.filter(::isReplayable)
                ?.forEach { replay(replayLogger, it) }
            ExecutionResult.Success
        } catch (exc: Throwable) {
            logger.trackInternalError(InternalErrorType.PayloadDeliveryFail, exc)
            // An unreadable payload will not become readable on retry, so let it be cleaned up.
            ExecutionResult.Incomplete(exc, retry = false)
        }
    }

    private fun isReplayable(log: Log): Boolean {
        val attrs = log.attributes ?: return false
        if (attrs.findAttributeValue(PrivateSpan.key) != null) {
            return false
        }
        return attrs.findAttributeValue(EMB_TYPE) in crashTypes
    }

    private fun replay(replayLogger: Logger, log: Log) {
        replayLogger.emit(
            body = log.body,
            timestamp = log.timeUnixNano,
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

    private companion object {
        const val EMB_TYPE = "emb.type"

        /** Marks a record that was persisted by a previous process and exported on a later launch. */
        const val REPLAYED_ATTRIBUTE = "emb.delivery.replayed"

        /**
         * Crashes captured while the process is dying, which is too late for live export. Native crashes are not
         * included: they are emitted on the next launch while the process is healthy, so live export delivers them.
         */
        val crashTypes = setOf(
            EmbType.System.Crash.value,
            EmbType.System.ReactNativeCrash.value,
        )
    }
}
