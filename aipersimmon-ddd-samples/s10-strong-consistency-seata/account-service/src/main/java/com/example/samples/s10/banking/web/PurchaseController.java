package com.example.samples.s10.banking.web;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s10.banking.application.PointsPurchase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The edge. Two endpoints, one per protocol, and neither of them says {@code @GlobalTransactional} — the
 * boundary belongs to the use case, not to the URL.
 */
@RestController
class PurchaseController {

  private final PointsPurchase purchases;
  private final JdbcTemplate jdbc;

  PurchaseController(PointsPurchase purchases, JdbcTemplate jdbc) {
    this.purchases = purchases;
    this.jdbc = jdbc;
  }

  @PostMapping("/purchases/at")
  PointsPurchase.Receipt purchaseWithAt(@Valid @RequestBody PurchaseRequest request) {
    return purchases.purchaseWithAtParticipant(request.toPurchase());
  }

  @PostMapping("/purchases/tcc")
  PointsPurchase.Receipt purchaseWithTcc(@Valid @RequestBody PurchaseRequest request) {
    return purchases.purchaseWithTccParticipant(request.toPurchase());
  }

  /** The tenant predicate is hand-written because a JdbcTemplate is outside the interceptor's reach. */
  @GetMapping("/accounts/{accountId}")
  AccountView view(@PathVariable String accountId) {
    return jdbc.queryForObject(
        "SELECT balance_minor, version FROM s10_account WHERE tenant_id = ? AND id = ?",
        (rs, row) ->
            new AccountView(accountId, rs.getLong("balance_minor"), rs.getLong("version")),
        TenantContext.effective().value(),
        accountId);
  }

  /**
   * @param thenFail makes the use case throw after both participants have written. A sample's affordance,
   *     and the only way to see the interesting half of a distributed transaction without a fault injector.
   * @param holdMillis keeps the global transaction open that long, so its locks can be observed.
   */
  record PurchaseRequest(
      @NotBlank String reference,
      @NotBlank String accountId,
      String pointsAccountId,
      @Positive long amountMinor,
      @Positive int points,
      boolean thenFail,
      long holdMillis) {

    PointsPurchase.Purchase toPurchase() {
      return new PointsPurchase.Purchase(
          reference,
          accountId,
          pointsAccountId == null || pointsAccountId.isBlank() ? accountId : pointsAccountId,
          amountMinor,
          points,
          thenFail,
          holdMillis);
    }
  }

  record AccountView(String accountId, long balanceMinor, long version) {}
}
