package com.example.samples.s25.refunds.infrastructure;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * A {@code uuid} column, read and written as a {@link UUID}. Fifteen lines, and it is here because the schema is not ours.
 *
 * <p>MyBatis ships type handlers for the JDBC types the specification names and no others, so PostgreSQL's {@code uuid} —
 * which is not one of them — has none. The failure is worth recognising because the message points at the wrong thing:
 * {@code "Type handler was null on parameter mapping for property 'publicId'"} reads like a mapping mistake, and the actual
 * situation is that the ORM has never heard of the column's type.
 *
 * <p>Three ways out, and this is the third:
 *
 * <ol>
 *   <li>map the property as {@code String}. Works only with {@code stringtype=unspecified} on the connection, which is a
 *       global setting that changes how every other statement binds strings — a large lever for a small problem;
 *   <li>change the column to {@code VARCHAR(36)}. A migration on a legacy table, to suit the ORM rather than the data;
 *   <li>write the handler. Local, explicit, and the thing that goes away if the column ever does.
 * </ol>
 *
 * <p>Which is the shape of most accommodations in this scenario: the schema is not going to change to suit the framework,
 * so the adapter absorbs the difference and says so in one place.
 *
 * <p>{@code setObject} without a target type on the way in, because the driver knows what a {@code uuid} is even though
 * MyBatis does not; {@code getObject(String, Class)} on the way out for the same reason.
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, parameter);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return rs.getObject(columnName, UUID.class);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return rs.getObject(columnIndex, UUID.class);
  }

  @Override
  public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return cs.getObject(columnIndex, UUID.class);
  }
}
