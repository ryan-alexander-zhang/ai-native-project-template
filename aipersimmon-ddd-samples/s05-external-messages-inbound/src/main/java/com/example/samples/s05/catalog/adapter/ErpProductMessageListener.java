package com.example.samples.s05.catalog.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s05.catalog.application.AdjustProductPrice;
import com.example.samples.s05.catalog.application.MirrorProductChange;
import com.example.samples.s05.catalog.domain.ChangeOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The anti-corruption layer: a plain {@code @KafkaListener}, because the framework's consumer bridge is
 * not applicable here.
 *
 * <p><strong>Why not the bridge.</strong> It subscribes to the topics this application's own
 * {@code @Externalized} events name, and it requires every record to carry well-formed CloudEvents
 * attributes — {@code ce_id}, {@code ce_source}, {@code ce_type}, {@code ce_specversion},
 * {@code ce_dataschemaversion} — because those are what the inbox key and the {@code (type, version)}
 * catalog lookup are made of. An ERP has none of them and will not grow them for us. So this listener
 * exists, and with it the three jobs the bridge would otherwise have done: deserialization, dedup, and
 * failure classification. S5 is where those become the application's problem.
 *
 * <p><strong>What it does and does not do.</strong> It translates and dispatches. It holds no rule, makes
 * no decision about whether a change is welcome, and touches no repository — the command channel does the
 * rest, with the same interceptors (validation, transaction, logging, tracing) an HTTP entry would get.
 * That symmetry is the reason to translate here rather than pass {@link ErpProductMessage} inward: a
 * message-driven use case and a request-driven one should differ only in how they were triggered.
 *
 * <p><strong>The classification, which this sample owns.</strong> The framework's bridge has three tiers
 * (poison → immediate dead letter; {@code DataAccessException} → retried forever, never dead-lettered;
 * everything else → bounded backoff then dead letter). Here the policy is deliberately simpler and
 * stated in one place:
 *
 * <ul>
 *   <li>{@link DataAccessException} → rethrown as-is, so the container retries. The database being
 *       unavailable is not the message's fault, and dead-lettering a valid message because of it would
 *       turn an outage into permanent data loss.
 *   <li><strong>everything else</strong> → {@link UntranslatableMessageException}, dead-lettered at once.
 *       That includes bugs in this code, and that is the intended trade: a record on the dead-letter
 *       topic is inspectable and replayable, while a poison message retried forever stops the partition
 *       and every other product's updates behind it.
 * </ul>
 *
 * A deployment that would rather stall than dead-letter can inverse this; what it must not do is leave
 * the decision implicit, because the default in that case is "retry forever" and nobody chose it.
 */
@Component
class ErpProductMessageListener {

  private static final Logger log = LoggerFactory.getLogger(ErpProductMessageListener.class);

  /** The only currency this context mirrors. Anything else is a message we must not silently accept. */
  private static final String MIRRORED_CURRENCY = "EUR";

  private final CommandBus commandBus;
  private final ObjectMapper objectMapper;

  ErpProductMessageListener(CommandBus commandBus, ObjectMapper objectMapper) {
    this.commandBus = commandBus;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = "${catalog.erp-topic}",
      groupId = "${spring.application.name}-erp",
      containerFactory = "erpListenerContainerFactory")
  void onErpMessage(ConsumerRecord<String, String> record) {
    ErpProductMessage message = parse(record.value());
    String kind = require(message.eventKind(), "event_kind", record);
    try {
      switch (kind) {
        case "PRODUCT_CHANGED" -> mirror(message, record);
        case "PRICE_REDUCED" -> reduce(message, record);
        // Not skipped: an unknown kind may be a message this consumer was supposed to grow into, and
        // silently dropping it is how an integration ends up missing half its traffic with nothing to
        // show for it. Dead-lettering keeps the evidence.
        default ->
            throw new UntranslatableMessageException(
                "unknown event_kind '" + kind + "' at " + at(record));
      }
    } catch (DataAccessException retryable) {
      // The one thing worth another attempt.
      throw retryable;
    } catch (UntranslatableMessageException permanent) {
      throw permanent;
    } catch (RuntimeException refused) {
      // A domain refusal, a validation failure, or a defect in this code. None of the three gets better
      // on a second attempt, and the record is preserved on the dead-letter topic either way.
      throw new UntranslatableMessageException(
          "refused ERP message at " + at(record) + ": " + refused.getMessage(), refused);
    }
  }

  private void mirror(ErpProductMessage message, ConsumerRecord<String, String> record) {
    ChangeOutcome outcome =
        commandBus.send(
            new MirrorProductChange(
                require(message.skuId(), "sku_id", record),
                requireRevision(message, record),
                require(message.displayName(), "display_name", record),
                priceCents(message, record)));
    // Worth logging at info: "superseded" is the normal outcome of a replay, and an operator who cannot
    // see it will read a quiet consumer as a broken one.
    log.info("mirrored ERP change for {} at {}: {}", message.skuId(), at(record), outcome);
  }

  private void reduce(ErpProductMessage message, ConsumerRecord<String, String> record) {
    Integer percent = message.reductionPercent();
    if (percent == null) {
      throw new UntranslatableMessageException("PRICE_REDUCED without reduction_percent at " + at(record));
    }
    // The refusal that matters. A relative effect with no id from the producer cannot be made safe:
    // there is no content to compare and nothing local to derive identity from that would survive a
    // replay. Guessing here (a payload hash, the record's offset) would look like dedup and would fail
    // exactly when it was needed. So this is a contract defect, reported as one.
    String messageId = message.messageId();
    if (messageId == null || messageId.isBlank()) {
      throw new UntranslatableMessageException(
          "PRICE_REDUCED without msg_id at "
              + at(record)
              + ": a relative change cannot be deduplicated without an id from the producer, and no"
              + " local substitute (payload hash, topic offset) survives a replay");
    }
    boolean applied =
        commandBus.send(
            new AdjustProductPrice(
                require(message.skuId(), "sku_id", record), percent, messageId));
    log.info(
        "ERP price reduction {} for {} at {}: {}",
        messageId,
        message.skuId(),
        at(record),
        applied ? "applied" : "already applied");
  }

  private ErpProductMessage parse(String payload) {
    try {
      return objectMapper.readValue(payload, ErpProductMessage.class);
    } catch (JsonProcessingException e) {
      // Unparseable is permanent by definition: the bytes will not improve.
      throw new UntranslatableMessageException("unparseable ERP payload", e);
    }
  }

  /**
   * The revision, which this consumer treats as required — and the reason it is the revision rather than
   * {@code changed_at}.
   *
   * <p>A timestamp from another system is a weak ordering key: clocks skew between the ERP's own nodes,
   * two changes can share a millisecond, and the format admits offsets that make comparison a parsing
   * question. A monotonic per-entity counter has none of those problems. Where an upstream offers only a
   * timestamp, the honest options are to use it and accept that same-instant changes are unordered, or
   * to ask for a counter — not to pretend the timestamp is one.
   */
  private long requireRevision(ErpProductMessage message, ConsumerRecord<String, String> record) {
    Long revision = message.revision();
    if (revision == null) {
      throw new UntranslatableMessageException(
          "PRODUCT_CHANGED without rev at "
              + at(record)
              + ": without a per-product ordering token a late message would overwrite a newer one");
    }
    return revision;
  }

  /** Decimal string to cents, refusing anything this context cannot hold exactly. */
  private long priceCents(ErpProductMessage message, ConsumerRecord<String, String> record) {
    ErpProductMessage.ErpPrice price = message.price();
    if (price == null || price.amount() == null) {
      throw new UntranslatableMessageException("PRODUCT_CHANGED without a price at " + at(record));
    }
    if (!MIRRORED_CURRENCY.equals(price.currency())) {
      throw new UntranslatableMessageException(
          "ERP price in '" + price.currency() + "' at " + at(record) + "; this catalog mirrors only "
              + MIRRORED_CURRENCY);
    }
    try {
      return new BigDecimal(price.amount()).movePointRight(2).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      // Sub-cent precision is not a rounding opportunity: rounding somebody else's money silently is
      // how a mirror stops being a mirror.
      throw new UntranslatableMessageException(
          "ERP price '" + price.amount() + "' at " + at(record) + " is not an exact number of cents", e);
    }
  }

  private String require(String value, String field, ConsumerRecord<String, String> record) {
    if (value == null || value.isBlank()) {
      throw new UntranslatableMessageException("ERP message without " + field + " at " + at(record));
    }
    return value;
  }

  /** Where the record was, for the log and the dead letter — the only use these coordinates have. */
  private static String at(ConsumerRecord<String, String> record) {
    return record.topic() + "-" + record.partition() + "@" + record.offset();
  }
}
