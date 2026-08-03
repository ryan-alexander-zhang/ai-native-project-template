package com.example.samples.s17.ordering.infrastructure;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis-Plus's own {@code JacksonTypeHandler} serialises to a {@code String} and binds it with
 * {@code setString}, which PostgreSQL refuses to assign to a {@code jsonb} column:
 *
 * <pre>column "shipping_address" is of type jsonb but expression is of type character varying</pre>
 *
 * <p>Three ways out, and this is the least invasive: bind the same JSON with {@code Types.OTHER} so the
 * server infers the type. The alternatives are declaring the column {@code text} (losing every JSON
 * operator and index) or setting {@code stringtype=unspecified} on the whole connection (which changes
 * how every string parameter in the application is bound, to fix one column).
 */
@MappedTypes(Object.class)
public class JsonbTypeHandler extends JacksonTypeHandler {

  public JsonbTypeHandler(Class<?> type) {
    super(type);
  }

  public JsonbTypeHandler(Class<?> type, Field field) {
    super(type, field);
  }

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, toJson(parameter), Types.OTHER);
  }
}
