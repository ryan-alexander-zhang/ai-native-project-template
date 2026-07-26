package com.aipersimmon.ddd.operationlog.cqrs.template;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reads a single named property from an object by invoking a <em>no-arg</em> accessor — a record
 * component accessor {@code name()} or a JavaBean getter {@code getName()}/{@code isName()}. This
 * is the only reflective surface the template engine exposes: no arguments, no arbitrary method
 * names, no field access. A missing accessor yields {@code null} (null-safe paths).
 */
final class PropertyAccess {

  private PropertyAccess() {}

  static Object read(Object target, String property) {
    if (target == null) {
      return null;
    }
    Method accessor = findAccessor(target.getClass(), property);
    if (accessor == null) {
      return null;
    }
    try {
      return accessor.invoke(target);
    } catch (IllegalAccessException | InvocationTargetException e) {
      return null;
    }
  }

  private static Method findAccessor(Class<?> type, String property) {
    Method direct = lookup(type, property);
    if (direct != null) {
      return direct;
    }
    String capitalized = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    Method getter = lookup(type, "get" + capitalized);
    return getter != null ? getter : lookup(type, "is" + capitalized);
  }

  private static Method lookup(Class<?> type, String name) {
    try {
      Method method = type.getMethod(name);
      return method.getParameterCount() == 0 ? method : null;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}
