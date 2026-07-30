package com.aipersimmon.ddd.persistence.mybatisplus;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which of a row's columns MyBatis-Plus would leave out of an update, so they can be
 * written back in explicitly.
 *
 * <p>MyBatis-Plus builds an entity update's {@code SET} clause by skipping fields that are null
 * (the default {@code NOT_NULL} strategy) — a sensible default for a <em>partial</em> update, where
 * null means "I am not saying anything about this column". Saving an aggregate is never partial:
 * {@code toRow} maps the whole root, so null there means "this field is empty now", and dropping
 * the assignment leaves the old value in the database. Nothing reports it. The optimistic-lock
 * check passes, because the version did move; the domain events publish, so downstream is told the
 * change happened; and the aggregate reads back with the old value the next time it is loaded.
 *
 * <p>So the columns MyBatis-Plus would omit are collected here and set explicitly. The rule mirrors
 * the one MyBatis-Plus itself applies when it decides whether to wrap an assignment in an {@code
 * <if>}, rather than assuming the default: a field the application configured {@code ALWAYS} is
 * already written and must not be written twice (Postgres rejects two assignments to one column),
 * and one configured {@code NEVER} is a column the application said to leave alone.
 */
final class ClearedColumns {

  private ClearedColumns() {}

  /**
   * Adds an explicit {@code column = null} to {@code wrapper} for every column the entity's own
   * {@code SET} clause would drop.
   *
   * @return how many were added, so a caller can say whether it had anything to correct
   */
  static <D> int forceOnto(UpdateWrapper<D> wrapper, D row) {
    TableInfo tableInfo = TableInfoHelper.getTableInfo(row.getClass());
    if (tableInfo == null) {
      // Not a MyBatis-Plus entity, so there is no metadata to reason from and no entity SET clause
      // to correct. Whatever else is wrong will surface at the update itself.
      return 0;
    }
    int forced = 0;
    for (TableFieldInfo field : omittedFields(tableInfo, row)) {
      wrapper.set(field.getColumn(), null);
      forced++;
    }
    return forced;
  }

  private static List<TableFieldInfo> omittedFields(TableInfo tableInfo, Object row) {
    List<TableFieldInfo> omitted = new ArrayList<>();
    for (TableFieldInfo field : tableInfo.getFieldList()) {
      if (isEmittedAnyway(tableInfo, field) || !isEmptyForItsStrategy(field, row)) {
        continue;
      }
      omitted.add(field);
    }
    return omitted;
  }

  /**
   * Whether the entity's own {@code SET} already carries this column, in which case adding it again
   * would produce {@code SET c = ?, c = null} — accepted by MySQL, rejected outright by PostgreSQL.
   *
   * <p>The version column is excluded for a different reason: it is the optimistic-lock interceptor
   * that decides what goes there, and it writes the incremented value onto the entity before the
   * statement is built. A logic-delete column is excluded because MyBatis-Plus deliberately keeps
   * it out of an ordinary update; deleting a row is not something saving an aggregate does.
   */
  private static boolean isEmittedAnyway(TableInfo tableInfo, TableFieldInfo field) {
    return field.isWithUpdateFill()
        || field.getPropertyType().isPrimitive()
        || field.getUpdateStrategy() == FieldStrategy.ALWAYS
        || field.getUpdateStrategy() == FieldStrategy.NEVER
        || field.equals(tableInfo.getVersionFieldInfo())
        || (tableInfo.isWithLogicDelete() && field.isLogicDelete());
  }

  /** Whether this field's value is one its configured strategy treats as "say nothing". */
  private static boolean isEmptyForItsStrategy(TableFieldInfo field, Object row) {
    Object value = read(field.getField(), row);
    if (field.getUpdateStrategy() == FieldStrategy.NOT_EMPTY
        && CharSequence.class.isAssignableFrom(field.getPropertyType())) {
      return value == null || ((CharSequence) value).isEmpty();
    }
    return value == null;
  }

  private static Object read(Field field, Object row) {
    try {
      field.setAccessible(true);
      return field.get(row);
    } catch (IllegalAccessException | RuntimeException e) {
      throw new IllegalStateException(
          "cannot read " + field.getDeclaringClass().getName() + "." + field.getName(), e);
    }
  }
}
