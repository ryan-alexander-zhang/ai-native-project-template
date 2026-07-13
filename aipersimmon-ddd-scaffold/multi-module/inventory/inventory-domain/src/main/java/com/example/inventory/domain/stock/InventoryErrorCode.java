package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The inventory context's catalogue of stable, machine-readable error codes. Same pure
 * {@link ErrorCode} pattern as ordering's {@code OrderingErrorCode} (transport-neutral,
 * per-BC enum) — but note inventory has <strong>no inbound HTTP surface</strong>: it
 * reacts to an integration event and reports failure as a {@code StockReservationFailed}
 * event, never an RFC 9457 response. So there is deliberately <em>no</em>
 * {@code ProblemCatalog}/{@code ProblemDescriptor} here (those are the HTTP boundary's
 * concern); instead the code travels on the failure event, giving the reacting saga a
 * stable identity to branch on. This is the event-driven counterpart to ordering's
 * HTTP-facing use of the same {@code ErrorCode} model (design-00003 §4.5/§4.7).
 */
public enum InventoryErrorCode implements ErrorCode {

    /** A reservation asked for more than is available (or for a non-positive quantity). */
    INSUFFICIENT_STOCK("inventory.insufficient-stock", ErrorCategory.DOMAIN_RULE),

    /** No stock record exists for the requested SKU. */
    STOCK_NOT_FOUND("inventory.stock-not-found", ErrorCategory.NOT_FOUND);

    private final String code;
    private final ErrorCategory category;

    InventoryErrorCode(String code, ErrorCategory category) {
        this.code = code;
        this.category = category;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
