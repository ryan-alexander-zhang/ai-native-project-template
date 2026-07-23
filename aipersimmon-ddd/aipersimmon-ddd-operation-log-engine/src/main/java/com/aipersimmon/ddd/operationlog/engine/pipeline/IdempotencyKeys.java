package com.aipersimmon.ddd.operationlog.engine.pipeline;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.model.OperationResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the default idempotency key for the CQRS path as {@code
 * SHA-256_hex(messageId|operationCode|outcome|completion)} — a fixed-width, collision-resistant key
 * whose result-kind component keeps a "failed then retried to success" pair as two distinct rows.
 */
final class IdempotencyKeys {

  private IdempotencyKeys() {}

  static String derive(String messageId, String operationCode, OperationResult result) {
    String raw =
        messageId + "|" + operationCode + "|" + result.outcome() + "|" + result.completion();
    return sha256Hex(raw);
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new OperationLogException("SHA-256 not available for idempotency key", e);
    }
  }
}
