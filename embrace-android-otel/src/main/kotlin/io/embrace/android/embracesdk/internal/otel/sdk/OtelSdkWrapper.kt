package io.embrace.android.embracesdk.internal.otel.sdk

import io.embrace.android.embracesdk.internal.config.instrumented.InstrumentedConfigImpl
import io.embrace.android.embracesdk.internal.config.instrumented.schema.OtelLimitsConfig
import io.embrace.android.embracesdk.internal.otel.config.OtelSdkConfig
import io.embrace.android.embracesdk.internal.otel.config.getMaxTotalAttributeCount
import io.embrace.android.embracesdk.internal.otel.config.getMaxTotalEventCount
import io.embrace.android.embracesdk.internal.otel.config.getMaxTotalLinkCount
import io.embrace.android.embracesdk.internal.otel.createSdkOtelInstance
import io.embrace.android.embracesdk.internal.otel.impl.EmbLoggerProvider
import io.embrace.android.embracesdk.internal.otel.impl.EmbOpenTelemetry
import io.embrace.android.embracesdk.internal.otel.impl.EmbTracerProvider
import io.embrace.android.embracesdk.internal.otel.logs.DefaultLogRecordProcessor
import io.embrace.android.embracesdk.internal.otel.logs.EventService
import io.embrace.android.embracesdk.internal.otel.logs.ExternalLogRecordExporter
import io.embrace.android.embracesdk.internal.otel.spans.SpanService
import io.embrace.android.embracesdk.internal.utils.EmbTrace
import io.opentelemetry.kotlin.Clock
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.logging.Logger
import io.opentelemetry.kotlin.logging.export.compositeLogRecordProcessor
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.export.compositeSpanProcessor

/**
 * Wrapper that instantiates a copy of the OpenTelemetry SDK configured with the appropriate settings and the given components so
 * the Embrace SDK can hook into its lifecycle. From this, the Embrace SDK can obtain an implementations of the OpenTelemetry API to
 * create OpenTelemetry primitives that it can use internally or export to any OpenTelemetry Collectors.
 */
class OtelSdkWrapper(
    otelClock: Clock,
    configuration: OtelSdkConfig,
    spanService: SpanService,
    eventService: EventService,
    limits: OtelLimitsConfig = InstrumentedConfigImpl.otelLimits,
    val useKotlinSdk: Boolean,
) {

    init {
        if (!useKotlinSdk) {
            // Enforce the use of default OTel Java SDK ThreadLocal ContextStorage to bypass SPI looking that violates Android strict mode
            System.setProperty("io.opentelemetry.context.contextStorageProvider", "default")
        }
    }

    val sdkTracer: Tracer by lazy {
        EmbTrace.trace("otel-tracer-init") {
            kotlinApi.tracerProvider.getTracer(
                name = configuration.sdkName,
                version = configuration.sdkVersion
            )
        }
    }

    val sdkLogger: Logger by lazy {
        EmbTrace.trace("otel-logger-init") {
            kotlinApi.loggerProvider.getLogger(
                name = configuration.sdkName,
                version = configuration.sdkVersion,
            )
        }
    }

    /**
     * A logger whose pipeline contains only the externally registered log processors and exporters, not
     * Embrace's own processor. It is used to replay telemetry that was persisted to disk in a previous process
     * (e.g. a crash) so it reaches the configured OTel destinations without being stored again by Embrace.
     *
     * Null when no external log export is configured.
     */
    @Suppress("SpreadOperator")
    val externalReplayLogger: Logger? by lazy {
        val processors = configuration.getExternalLogRecordProcessors()
        val exporters = configuration.getExternalLogExporters()
        if (processors.isEmpty() && exporters.isEmpty()) {
            return@lazy null
        }
        EmbTrace.trace("otel-replay-logger-init") {
            val exportProcessors = when {
                exporters.isEmpty() -> processors
                else -> processors + DefaultLogRecordProcessor(ExternalLogRecordExporter(exporters))
            }
            createSdkOtelInstance(
                useKotlinSdk = useKotlinSdk,
                loggerProvider = {
                    resource(attributes = configuration.resourceAction)
                    logLimits {
                        attributeCountLimit = limits.getMaxTotalAttributeCount()
                    }
                    export {
                        compositeLogRecordProcessor(*exportProcessors.toTypedArray())
                    }
                },
                clock = otelClock,
            ).loggerProvider.getLogger(
                name = configuration.sdkName,
                version = configuration.sdkVersion,
            )
        }
    }

    @Suppress("DEPRECATION", "SpreadOperator")
    private val kotlinApi: OpenTelemetry by lazy {
        createSdkOtelInstance(
            useKotlinSdk = useKotlinSdk,
            tracerProvider = {
                resource(attributes = configuration.resourceAction)
                spanLimits {
                    eventCountLimit = limits.getMaxTotalEventCount()
                    attributeCountLimit = limits.getMaxTotalAttributeCount()
                    linkCountLimit = limits.getMaxTotalLinkCount()
                }
                export {
                    val processors = listOf(configuration.spanProcessor) + configuration.getExternalSpanProcessors()
                    compositeSpanProcessor(*processors.toTypedArray())
                }
            },
            loggerProvider = {
                resource(attributes = configuration.resourceAction)
                logLimits {
                    attributeCountLimit = limits.getMaxTotalAttributeCount()
                }
                export {
                    val processors = listOf(configuration.logRecordProcessor) + configuration.getExternalLogRecordProcessors()
                    compositeLogRecordProcessor(*processors.toTypedArray())
                }
            },
            clock = otelClock,
        )
    }

    val openTelemetryKotlin: OpenTelemetry by lazy {
        EmbOpenTelemetry(
            impl = kotlinApi,
            traceProviderSupplier = { EmbTracerProvider(kotlinApi, spanService, otelClock, useKotlinSdk) },
            loggerProviderSupplier = { EmbLoggerProvider(kotlinApi, eventService) }
        )
    }
}
