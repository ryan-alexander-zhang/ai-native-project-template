package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command to reserve stock for an order: the order id and the lines to reserve. No result.
 *
 * <p>This command arrives from an integration-event listener, not from HTTP, which is exactly why
 * its Bean Validation constraints matter: the command bus enforces them for every entry point, so
 * an inbound event with a malformed payload is rejected the same way a bad HTTP request would be —
 * there is no web adapter here to guard it.
 */
@OperationLog(
    code = "inventory.stock.reserve",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Reserved stock for order ${input.orderId}",
    failure =
        "Stock reservation for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record ReserveStock(@NotBlank String orderId, @NotEmpty List<@Valid Line> lines)
    implements Command<Void> {

  public ReserveStock {
    // Defensive copy: keep this immutable command isolated from later mutation of the
    // caller's list. Null is left as-is so @NotEmpty still reports it as a validation error.
    // The copy stays here rather than inside the helper so it is locally evident (and so
    // SpotBugs can see it — EI_EXPOSE_REP does not follow the call).
    lines = lines == null ? null : List.copyOf(mergeLinesRepeatingASku(lines));
  }

  /**
   * Collapses lines that name the same SKU into one, summing their quantities — {@code (SKU-1, 2),
   * (SKU-1, 3)} becomes {@code (SKU-1, 5)}.
   *
   * <p>"At most one line per SKU" is a precondition of how inventory reserves: the handler holds
   * one {@link com.example.inventory.domain.stock.Stock} instance per SKU, and two lines naming one
   * SKU would otherwise be two claims on the same aggregate. Until now that precondition held only
   * because <em>ordering</em> enforces {@code OrderHasDistinctSkus} on its own aggregate — a
   * bounded context relying on an invariant belonging to another one, across a published language,
   * an outbox, Kafka and an inbox, with nothing to keep the two in step (issue-00076). Inventory
   * now establishes it for itself, on the command, where the precondition belongs.
   *
   * <p>Merging rather than rejecting: a caller asking for two of a SKU and then three more has an
   * unambiguous intent, and honouring it is friendlier than a validation error the caller cannot
   * act on. Malformed input is left exactly as it arrived so Bean Validation still reports the real
   * problem — merging a {@code quantity} of 0 into a positive one would hide a {@code @Positive}
   * violation rather than surface it.
   */
  private static List<Line> mergeLinesRepeatingASku(List<Line> lines) {
    if (!wellFormed(lines)) {
      return lines;
    }
    Map<String, Integer> quantityBySku = new LinkedHashMap<>();
    for (Line line : lines) {
      quantityBySku.merge(line.sku(), line.quantity(), Integer::sum);
    }
    if (quantityBySku.size() == lines.size()) {
      return lines;
    }
    List<Line> merged = new ArrayList<>(quantityBySku.size());
    quantityBySku.forEach((sku, quantity) -> merged.add(new Line(sku, quantity)));
    return merged;
  }

  /** Only well-formed lines are merged; anything else is left for Bean Validation to report. */
  private static boolean wellFormed(List<Line> lines) {
    for (Line line : lines) {
      if (line == null || line.sku() == null || line.sku().isBlank() || line.quantity() <= 0) {
        return false;
      }
    }
    return true;
  }

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
