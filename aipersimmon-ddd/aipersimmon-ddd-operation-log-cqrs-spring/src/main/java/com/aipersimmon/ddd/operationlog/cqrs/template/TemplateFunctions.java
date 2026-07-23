package com.aipersimmon.ddd.operationlog.cqrs.template;

import java.util.List;
import java.util.Map;

/**
 * The pure-function whitelist for templates. All functions are side-effect free and operate only on
 * their evaluated arguments: {@code mask} redacts a value, {@code truncate} bounds its length,
 * {@code defaultValue} substitutes when blank. The arities are validated at compile time.
 */
final class TemplateFunctions {

  static final Map<String, Integer> ARITY = Map.of("mask", 1, "truncate", 2, "defaultValue", 2);

  private TemplateFunctions() {}

  static Object apply(String name, List<Object> args) {
    return switch (name) {
      case "mask" -> mask(asString(args.get(0)));
      case "truncate" -> truncate(asString(args.get(0)), asInt(args.get(1)));
      case "defaultValue" -> defaultValue(args.get(0), args.get(1));
      default -> throw new IllegalStateException("unknown template function: " + name);
    };
  }

  private static String mask(String value) {
    if (value == null) {
      return null;
    }
    if (value.length() <= 2) {
      return "**";
    }
    return value.charAt(0) + "***" + value.charAt(value.length() - 1);
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  private static Object defaultValue(Object value, Object fallback) {
    if (value == null || asString(value).isBlank()) {
      return fallback;
    }
    return value;
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static int asInt(Object value) {
    if (value instanceof Integer i) {
      return i;
    }
    return Integer.parseInt(String.valueOf(value));
  }
}
