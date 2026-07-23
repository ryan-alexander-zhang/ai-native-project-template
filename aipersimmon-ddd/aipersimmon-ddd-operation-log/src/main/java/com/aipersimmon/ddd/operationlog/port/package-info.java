/**
 * The ports of the Operation Log component: {@link
 * com.aipersimmon.ddd.operationlog.port.OperationLogs} (the single explicit entry for business
 * code), the outbound {@link com.aipersimmon.ddd.operationlog.port.OperationLogSink}, the read-side
 * {@link com.aipersimmon.ddd.operationlog.port.OperationLogReader} (implemented in P3), and their
 * sealed result / query value types. Interfaces only; implementations live in the engine and
 * storage backends.
 */
package com.aipersimmon.ddd.operationlog.port;
