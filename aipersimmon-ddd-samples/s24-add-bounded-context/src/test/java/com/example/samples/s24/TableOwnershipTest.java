package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * Every table a context queries belongs to that context. Read out of the SQL, not out of anybody's memory.
 *
 * <p><strong>This is the most useful test in the sample, and it is the least sophisticated.</strong> The catalogue's last
 * question is when a context should become its own deployment unit and what has to change first, and the honest answer is
 * that the code usually decided that long before anybody asked — with one join. A join across a context boundary works,
 * is faster than asking, needs no interface, and passes every architecture rule there is, because ArchUnit reads Java
 * and a table name is a string.
 *
 * <p>So this reads the strings. It walks the mapper interfaces, pulls the SQL out of the MyBatis annotations, finds every
 * {@code s24_*} identifier in it, and checks the prefix against the package the mapper lives in. Two contexts, one join,
 * and the build goes red the same day it was written rather than on the day somebody tries to split the service.
 *
 * <p>It is deliberately crude — a regex over annotation values. It would miss SQL in an XML mapper, in a string constant,
 * or built at runtime. The answer to that is not to make it clever: it is that the same crude check applied to a
 * migration file, or to a repository-wide grep, catches those too, and a crude check that runs is worth more than a
 * thorough one that does not exist.
 */
class TableOwnershipTest {

  /** Which package prefix owns which table prefix. Adding a context adds one line here. */
  private static final Map<String, String> OWNERSHIP =
      Map.of(
          "com.example.samples.s24.ordering", "s24_ordering_",
          "com.example.samples.s24.inventory", "s24_inventory_",
          "com.example.samples.s24.coupons", "s24_coupons_");

  private static final Pattern TABLE = Pattern.compile("\\bs24_[a-z_]+\\b");

  private static final Set<Class<? extends Annotation>> SQL_ANNOTATIONS =
      Set.of(Select.class, Insert.class, Update.class, Delete.class);

  @Test
  void nomapperQueriesAnotherContextsTables() {
    Map<String, List<String>> trespasses = new LinkedHashMap<>();
    for (Class<?> mapper : mapperInterfaces()) {
      String owner = ownerOf(mapper.getName());
      if (owner == null) {
        continue;
      }
      String allowedPrefix = OWNERSHIP.get(owner);
      for (String table : tablesNamedBy(mapper)) {
        if (!table.startsWith(allowedPrefix)) {
          trespasses
              .computeIfAbsent(mapper.getSimpleName(), k -> new ArrayList<>())
              .add(table);
        }
      }
    }
    assertThat(trespasses)
        .as("a query across a context boundary is the boundary not existing")
        .isEmpty();
  }

  /** And the check is worth nothing if it never looks at anything, so this asserts it did. */
  @Test
  void thecheckActuallyReadsSomeSql() {
    List<String> tables = new ArrayList<>();
    for (Class<?> mapper : mapperInterfaces()) {
      tables.addAll(tablesNamedBy(mapper));
    }
    assertThat(tables)
        .as("the mappers whose SQL was scanned")
        .contains("s24_ordering_order_line", "s24_coupons_redemption");
    assertThat(mapperInterfaces()).as("mappers found on disk").hasSizeGreaterThanOrEqualTo(5);
  }

  /**
   * The entity-to-table mapping is in an annotation rather than in SQL, so it is checked the same way.
   *
   * <p>{@code @TableName} is how the generated CRUD learns which table to write, and it is the other place a context
   * could reach across — a row class pointed at somebody else's table, with no SQL written anywhere.
   */
  @Test
  void norowClassIsMappedOntoAnotherContextsTable() {
    Map<String, String> trespasses = new LinkedHashMap<>();
    for (Class<?> row : classesUnder("com.example.samples.s24")) {
      com.baomidou.mybatisplus.annotation.TableName tableName =
          row.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class);
      if (tableName == null) {
        continue;
      }
      String owner = ownerOf(row.getName());
      if (owner != null && !tableName.value().startsWith(OWNERSHIP.get(owner))) {
        trespasses.put(row.getSimpleName(), tableName.value());
      }
    }
    assertThat(trespasses).isEmpty();
  }

  private static String ownerOf(String className) {
    return OWNERSHIP.keySet().stream()
        .filter(prefix -> className.startsWith(prefix + "."))
        .findFirst()
        .orElse(null);
  }

  private static List<String> tablesNamedBy(Class<?> mapper) {
    List<String> tables = new ArrayList<>();
    for (Method method : mapper.getDeclaredMethods()) {
      for (Class<? extends Annotation> annotation : SQL_ANNOTATIONS) {
        Annotation present = method.getAnnotation(annotation);
        if (present == null) {
          continue;
        }
        for (String sql : sqlOf(present)) {
          Matcher matcher = TABLE.matcher(sql.toLowerCase(Locale.ROOT));
          while (matcher.find()) {
            tables.add(matcher.group());
          }
        }
      }
    }
    return tables;
  }

  private static String[] sqlOf(Annotation annotation) {
    try {
      return (String[]) annotation.annotationType().getMethod("value").invoke(annotation);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read the SQL out of " + annotation, e);
    }
  }

  private static List<Class<?>> mapperInterfaces() {
    return classesUnder("com.example.samples.s24").stream()
        .filter(Class::isInterface)
        .filter(type -> type.getSimpleName().endsWith("Mapper"))
        .toList();
  }

  /** Walks the compiled classes rather than using a scanning library, so nothing is configured to be missed. */
  private static List<Class<?>> classesUnder(String basePackage) {
    Path root = Path.of("target/classes");
    List<Class<?>> classes = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      paths
          .filter(path -> path.toString().endsWith(".class"))
          .forEach(
              path -> {
                String name =
                    root.relativize(path)
                        .toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (!name.startsWith(basePackage) || name.endsWith("package-info")) {
                  return;
                }
                try {
                  classes.add(Class.forName(name, false, TableOwnershipTest.class.getClassLoader()));
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                  // Not every compiled name is loadable in isolation; the ones that matter are.
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot walk " + root, e);
    }
    return classes;
  }
}
