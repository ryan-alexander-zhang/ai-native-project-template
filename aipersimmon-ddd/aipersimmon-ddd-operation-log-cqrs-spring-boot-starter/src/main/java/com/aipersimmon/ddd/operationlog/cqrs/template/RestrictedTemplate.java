package com.aipersimmon.ddd.operationlog.cqrs.template;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A template compiled once at startup from a string containing {@code ${ expr }} placeholders over
 * a fixed set of allowlisted roots. {@code expr} is either a null-safe property path ({@code
 * input.order.id}) or a whitelisted function call ({@code mask(input.phone)}, {@code
 * truncate(input.note, 50)}, {@code defaultValue(input.reason, 'n/a')}). Unknown roots, unknown
 * functions, wrong arities, and malformed placeholders fail at {@link #compile} — i.e. at startup.
 */
public final class RestrictedTemplate {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern INTEGER = Pattern.compile("-?\\d+");

  private final List<Object> nodes; // String literals and Expression fragments

  private RestrictedTemplate(List<Object> nodes) {
    this.nodes = nodes;
  }

  /** Compile the template, validating roots/functions/arities against the allowed roots. */
  public static RestrictedTemplate compile(String template, Set<String> allowedRoots) {
    List<Object> nodes = new ArrayList<>();
    int i = 0;
    while (i < template.length()) {
      int start = template.indexOf("${", i);
      if (start < 0) {
        nodes.add(template.substring(i));
        break;
      }
      if (start > i) {
        nodes.add(template.substring(i, start));
      }
      int end = template.indexOf('}', start + 2);
      if (end < 0) {
        throw new OperationLogException("unterminated ${ in template: " + template);
      }
      nodes.add(parse(template.substring(start + 2, end).trim(), allowedRoots));
      i = end + 1;
    }
    return new RestrictedTemplate(nodes);
  }

  /** Render the template against the root objects; a null expression renders as empty. */
  public String render(Map<String, Object> roots) {
    StringBuilder out = new StringBuilder();
    for (Object node : nodes) {
      if (node instanceof String literal) {
        out.append(literal);
      } else {
        Object value = ((Expression) node).eval(roots);
        out.append(value == null ? "" : value);
      }
    }
    return out.toString();
  }

  private static Expression parse(String expr, Set<String> allowedRoots) {
    if (expr.isEmpty()) {
      throw new OperationLogException("empty ${} expression");
    }
    if (expr.length() >= 2 && expr.charAt(0) == '\'' && expr.charAt(expr.length() - 1) == '\'') {
      return new Literal(expr.substring(1, expr.length() - 1));
    }
    if (INTEGER.matcher(expr).matches()) {
      return new IntLiteral(Integer.parseInt(expr));
    }
    int paren = expr.indexOf('(');
    if (paren > 0 && expr.endsWith(")")) {
      return function(expr, paren, allowedRoots);
    }
    return path(expr, allowedRoots);
  }

  private static Expression function(String expr, int paren, Set<String> allowedRoots) {
    String name = expr.substring(0, paren).trim();
    Integer arity = TemplateFunctions.ARITY.get(name);
    if (arity == null) {
      throw new OperationLogException("unknown template function: " + name);
    }
    List<String> rawArgs = splitArgs(expr.substring(paren + 1, expr.length() - 1));
    if (rawArgs.size() != arity) {
      throw new OperationLogException(
          "function " + name + " expects " + arity + " args but got " + rawArgs.size());
    }
    List<Expression> args = new ArrayList<>();
    for (String raw : rawArgs) {
      args.add(parse(raw.trim(), allowedRoots));
    }
    return new FunctionCall(name, args);
  }

  private static Expression path(String expr, Set<String> allowedRoots) {
    List<String> segments = List.of(expr.split("\\."));
    for (String segment : segments) {
      if (!IDENTIFIER.matcher(segment).matches()) {
        throw new OperationLogException("illegal path segment '" + segment + "' in: " + expr);
      }
    }
    if (!allowedRoots.contains(segments.get(0))) {
      throw new OperationLogException(
          "unknown template root '" + segments.get(0) + "'; allowed: " + allowedRoots);
    }
    return new Path(segments);
  }

  private static List<String> splitArgs(String args) {
    if (args.isBlank()) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '\'') {
        inQuote = !inQuote;
        current.append(c);
      } else if (c == ',' && !inQuote) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(current.toString());
    return parts;
  }

  private interface Expression {
    Object eval(Map<String, Object> roots);
  }

  private record Literal(String value) implements Expression {
    @Override
    public Object eval(Map<String, Object> roots) {
      return value;
    }
  }

  private record IntLiteral(int value) implements Expression {
    @Override
    public Object eval(Map<String, Object> roots) {
      return value;
    }
  }

  private record Path(List<String> segments) implements Expression {
    @Override
    public Object eval(Map<String, Object> roots) {
      Object current = roots.get(segments.get(0));
      for (int i = 1; i < segments.size() && current != null; i++) {
        current = PropertyAccess.read(current, segments.get(i));
      }
      return current;
    }
  }

  private record FunctionCall(String name, List<Expression> args) implements Expression {
    @Override
    public Object eval(Map<String, Object> roots) {
      List<Object> values = new ArrayList<>();
      for (Expression arg : args) {
        values.add(arg.eval(roots));
      }
      return TemplateFunctions.apply(name, values);
    }
  }
}
