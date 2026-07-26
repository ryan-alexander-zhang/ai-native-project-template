package com.aipersimmon.ddd.persistence.mybatisplus;

/**
 * A data object whose table carries the optimistic-lock version column.
 *
 * <p>Implemented by the row type a {@link MybatisPlusAggregateRepository} writes, so the base class
 * can move the aggregate's loaded version onto the row without reflection — and so a row type that
 * forgot its version field fails to compile rather than losing the version check at runtime.
 *
 * <p>The field itself still needs MyBatis-Plus's {@code @Version} annotation: that is what makes
 * the optimistic-locker interceptor rewrite an update into {@code SET version = version + 1 ...
 * WHERE version = ?}. This interface only guarantees the value can be carried.
 *
 * <pre>{@code
 * public class OrderDo implements VersionedRow {
 *   @Version private Long version;
 *   // getVersion / setVersion
 * }
 * }</pre>
 */
public interface VersionedRow {

  /** The version currently set on this row. */
  Long getVersion();

  /** Set the version the write should check against (or insert with). */
  void setVersion(Long version);
}
