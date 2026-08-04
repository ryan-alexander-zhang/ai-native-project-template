package com.example.samples.s10;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * What a distributed transaction actually buys, measured across two services and two databases.
 *
 * <p>Every assertion here is about a pair of rows in two different PostgreSQL instances. None of it can be
 * observed from inside either service, which is why this module exists.
 */
class DistributedTransactionTest extends DistributedTransactionTestBase {

  @Test
  void bothdatabasesKeepTheirWriteWhenTheGlobalTransactionCommits() {
    Map<?, ?> receipt = purchase("at", request("buy-1", "customer-1"));

    assertThat(receipt.get("mode")).isEqualTo("AT");
    assertThat((String) receipt.get("xid")).isNotBlank();
    assertThat(balanceOf("customer-1")).isEqualTo(97500);
    assertThat(awardedTo("customer-1")).isEqualTo(25);

    // Both undo logs are cleaned up, but asynchronously: on commit Seata's AsyncWorker deletes them after
    // the caller already has its answer. That is why global commit is cheap, and why undo_log has a floor
    // under load rather than being empty at rest.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(accountUndoLogRows()).isZero();
              assertThat(pointsUndoLogRows()).isZero();
            });
  }

  @Test
  void neitherdatabaseKeepsItsWriteWhenTheGlobalTransactionRollsBack() {
    Map<String, Object> body = request("buy-2", "customer-1");
    body.put("thenFail", true);

    assertThat(purchaseExpectingFailure("at", body)).isNotNull();

    // The money came back, and so did the version — which matters more than it looks. A rollback that
    // restored the balance but left version at 2 would leave the row permanently unwritable by anyone
    // holding a version-1 snapshot, and the optimistic lock would be reporting a conflict that no longer
    // exists. The undo log carries version as an ordinary column precisely so this works.
    assertThat(balanceOf("customer-1")).isEqualTo(100000);
    assertThat(accountVersionOf("customer-1")).isEqualTo(1);
    assertThat(awardedTo("customer-1")).isZero();

    // Rollback deletes the undo log inline, unlike commit.
    assertThat(accountUndoLogRows()).isZero();
    assertThat(pointsUndoLogRows()).isZero();
  }

  @Test
  void theatParticipantRefusesToWriteWhenTheCallerForgotTheXid() {
    int status =
        postToPointsDirectly(
            "/awards", Map.of("reference", "loose-1", "accountId", "customer-1", "points", 5), null);

    assertThat(status).isEqualTo(409);
    assertThat(awardedTo("customer-1")).isZero();
  }

  @Test
  void thetccParticipantRefusesToWriteWhenTheCallerPropagatedAnXid() {
    int status =
        postToPointsDirectly(
            "/reservations",
            Map.of("reference", "loose-2", "accountId", "customer-1", "points", 5),
            "172.17.0.1:8091:1234567890");

    assertThat(status).isEqualTo(409);
    assertThat(frozenFor("customer-1")).isZero();
    assertThat(entryStateOf("loose-2")).isNull();
  }

  @Test
  void thetccPathPromisesThenConfirms() {
    purchase("tcc", request("buy-3", "customer-1"));

    assertThat(balanceOf("customer-1")).isEqualTo(97500);
    // Confirm happens after the global transaction commits, on Seata's own thread — so this is the one
    // assertion in the sample that has to wait for something the caller was never told about.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(awardedTo("customer-1")).isEqualTo(25);
              assertThat(frozenFor("customer-1")).isZero();
              assertThat(entryStateOf("buy-3")).isEqualTo("AWARDED");
            });
  }

  @Test
  void thetccPathPromisesThenCancels() {
    Map<String, Object> body = request("buy-4", "customer-1");
    body.put("thenFail", true);

    assertThat(purchaseExpectingFailure("tcc", body)).isNotNull();

    // The debit is an AT branch, so it is undone by the coordinator. The points are a TCC branch, so they
    // are undone by the participant's own Cancel — and the ledger keeps the cancellation, where AT's
    // rollback would have left no trace that anything was ever promised.
    assertThat(balanceOf("customer-1")).isEqualTo(100000);
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(awardedTo("customer-1")).isZero();
              assertThat(frozenFor("customer-1")).isZero();
              assertThat(entryStateOf("buy-4")).isEqualTo("CANCELLED");
            });
  }

  @Test
  void areferenceThatWasCancelledCanNeverBeReservedAfterwards() {
    // Cancel arriving before its own Try is Seata's suspension hazard: the coordinator timed the branch out
    // while the Try request was still in flight. Simulated here by calling the phases out of order.
    int cancelled =
        postToPointsDirectly(
            "/reservations/late-1/cancel", Map.of("accountId", "customer-1", "points", 7), null);
    assertThat(cancelled).isEqualTo(200);
    assertThat(entryStateOf("late-1")).isEqualTo("CANCELLED");

    int lateTry =
        postToPointsDirectly(
            "/reservations",
            Map.of("reference", "late-1", "accountId", "customer-1", "points", 7),
            null);

    // Refused. Had it succeeded, 7 points would be frozen forever: the branch is already finished, so no
    // Confirm and no Cancel is ever coming for them.
    assertThat(lateTry).isEqualTo(409);
    assertThat(frozenFor("customer-1")).isZero();
  }

  /**
   * The measurement the whole sample is built to make: what AT's global lock costs, and what TCC buys by not
   * taking one.
   *
   * <p>Two purchases from two different bank accounts, both awarding points to one shared loyalty row. The
   * bank accounts differ so that the only possible contention is the points row.
   */
  @Test
  void atholdsThePointsRowForTheWholeTransactionAndTccDoesNot() {
    // --- AT: the second purchase cannot have the row -------------------------------------------------
    Map<String, Object> slow = request("hold-at", "customer-1");
    slow.put("pointsAccountId", "shared-loyalty");
    slow.put("holdMillis", 4000);

    CompletableFuture<Map<?, ?>> first = CompletableFuture.supplyAsync(() -> purchase("at", slow));

    // Wait until the first transaction's points branch has actually committed — the undo-log row is the
    // proof, and it is also the moment the global lock on that row starts being held.
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> pointsUndoLogRows() >= 1);

    // Mid-flight, and this is what "strong consistency" does NOT mean. Both branches have committed their
    // local transactions, so both changes are visible to any ordinary reader while the global transaction is
    // still undecided. What the global lock buys is that no other *global* transaction can act on them — not
    // that they cannot be seen.
    assertThat(balanceOf("customer-1")).isEqualTo(97500);
    assertThat(awardedTo("shared-loyalty")).isEqualTo(25);

    Map<String, Object> contender = request("contend-at", "customer-2");
    contender.put("pointsAccountId", "shared-loyalty");
    Throwable refused = purchaseExpectingFailure("at", contender);

    assertThat(refused).as("a second global transaction must not get the locked row").isNotNull();
    first.join();

    // The first one committed; the second one changed nothing at all — not even its own bank account, which
    // it had already debited before reaching the points service.
    assertThat(awardedTo("shared-loyalty")).isEqualTo(25);
    assertThat(balanceOf("customer-1")).isEqualTo(97500);
    assertThat(balanceOf("customer-2")).isEqualTo(100000);

    // And it was contention, not a permanent refusal: the identical request succeeds now that the lock is
    // gone. This is what distinguishes "the row was busy" from "the request was wrong".
    purchase("at", request("contend-at-again", "customer-2"));
    assertThat(balanceOf("customer-2")).isEqualTo(97500);

    // --- TCC: the second purchase gets the row ------------------------------------------------------
    Map<String, Object> slowTcc = request("hold-tcc", "customer-1");
    slowTcc.put("pointsAccountId", "shared-loyalty");
    slowTcc.put("holdMillis", 4000);

    CompletableFuture<Map<?, ?>> firstTcc =
        CompletableFuture.supplyAsync(() -> purchase("tcc", slowTcc));

    // Try has committed and released everything; the promise lives in `frozen`, not in a lock.
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> "RESERVED".equals(entryStateOf("hold-tcc")));

    Map<String, Object> contenderTcc = request("contend-tcc", "customer-2");
    contenderTcc.put("pointsAccountId", "shared-loyalty");
    Map<?, ?> secondReceipt = purchase("tcc", contenderTcc);

    assertThat(secondReceipt).as("TCC releases the row at Try, so this must go through").isNotNull();
    firstTcc.join();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(entryStateOf("hold-tcc")).isEqualTo("AWARDED");
              assertThat(entryStateOf("contend-tcc")).isEqualTo("AWARDED");
              assertThat(frozenFor("shared-loyalty")).isZero();
            });
    // 25 from the AT run above, plus 25 and 25 from the two TCC runs.
    assertThat(awardedTo("shared-loyalty")).isEqualTo(75);
  }
}
