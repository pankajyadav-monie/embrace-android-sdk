package io.embrace.android.embracesdk.internal.otel

import io.opentelemetry.kotlin.export.OperationResultCode

/**
 * Exports [telemetry] to every exporter, even if an earlier one fails or throws. Returns [OperationResultCode.Success]
 * only if all of them succeeded.
 */
internal suspend fun <E, T> List<E>.exportToAll(
    telemetry: List<T>,
    export: suspend E.(List<T>) -> OperationResultCode,
): OperationResultCode {
    if (telemetry.isEmpty()) {
        return OperationResultCode.Success
    }
    var result: OperationResultCode = OperationResultCode.Success
    forEach { exporter ->
        try {
            if (exporter.export(telemetry) != OperationResultCode.Success) {
                result = OperationResultCode.Failure
            }
        } catch (ignored: Throwable) {
            result = OperationResultCode.Failure
        }
    }
    return result
}
