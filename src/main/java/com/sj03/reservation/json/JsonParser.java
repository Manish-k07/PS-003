package com.sj03.reservation.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free recursive-descent JSON parser preserving object insertion order.
 */
public final class JsonParser {
  private final String input;
  private int index;

  private JsonParser(String input) {
    this.input = input;
    this.index = 0;
  }

  /** Parses a JSON document string into Maps, Lists, Strings, Numbers, Booleans, or null. */
  public static Object parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("JSON input cannot be null");
    }
    JsonParser parser = new JsonParser(json);
    parser.skipWhitespace();
    Object value = parser.readValue();
    parser.skipWhitespace();
    if (parser.index < parser.input.length()) {
      throw parser.error("Unexpected trailing characters");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object parsed = parse(json);
    if (!(parsed instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Expected JSON object at root");
    }
    return (Map<String, Object>) parsed;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> parseArray(String json) {
    Object parsed = parse(json);
    if (!(parsed instanceof List<?>)) {
      throw new IllegalArgumentException("Expected JSON array at root");
    }
    return (List<Object>) parsed;
  }

  private Object readValue() {
    skipWhitespace();
    if (index >= input.length()) {
      throw error("Unexpected end of JSON input");
    }
    char ch = input.charAt(index);
    return switch (ch) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't', 'f' -> readBoolean();
      case 'n' -> readNull();
      default -> {
        if (ch == '-' || (ch >= '0' && ch <= '9')) {
          yield readNumber();
        }
        throw error("Unexpected character '" + ch + "'");
      }
    };
  }

  private Map<String, Object> readObject() {
    expect('{');
    Map<String, Object> map = new LinkedHashMap<>();
    skipWhitespace();
    if (peek('}')) {
      index++;
      return map;
    }
    while (true) {
      skipWhitespace();
      if (!peek('"')) {
        throw error("Expected string key in object");
      }
      String key = readString();
      skipWhitespace();
      expect(':');
      Object value = readValue();
      map.put(key, value);
      skipWhitespace();
      if (peek(',')) {
        index++;
      } else if (peek('}')) {
        index++;
        return map;
      } else {
        throw error("Expected ',' or '}' in object");
      }
    }
  }

  private List<Object> readArray() {
    expect('[');
    List<Object> list = new ArrayList<>();
    skipWhitespace();
    if (peek(']')) {
      index++;
      return list;
    }
    while (true) {
      list.add(readValue());
      skipWhitespace();
      if (peek(',')) {
        index++;
      } else if (peek(']')) {
        index++;
        return list;
      } else {
        throw error("Expected ',' or ']' in array");
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (index < input.length()) {
      char ch = input.charAt(index++);
      if (ch == '"') {
        return sb.toString();
      }
      if (ch < 0x20) {
        throw error("Unescaped control character in string");
      }
      if (ch == '\\') {
        if (index >= input.length()) {
          throw error("Unterminated escape sequence");
        }
        char esc = input.charAt(index++);
        switch (esc) {
          case '"', '\\', '/' -> sb.append(esc);
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (index + 4 > input.length()) {
              throw error("Invalid unicode escape");
            }
            String hex = input.substring(index, index + 4);
            try {
              sb.append((char) Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
              throw error("Invalid hex in unicode escape: " + hex);
            }
            index += 4;
          }
          default -> throw error("Invalid escape sequence '\\" + esc + "'");
        }
      } else {
        sb.append(ch);
      }
    }
    throw error("Unterminated string literal");
  }

  private Boolean readBoolean() {
    if (input.startsWith("true", index)) {
      index += 4;
      return Boolean.TRUE;
    }
    if (input.startsWith("false", index)) {
      index += 5;
      return Boolean.FALSE;
    }
    throw error("Invalid boolean literal");
  }

  private Object readNull() {
    if (input.startsWith("null", index)) {
      index += 4;
      return null;
    }
    throw error("Invalid null literal");
  }

  private Number readNumber() {
    int start = index;
    if (peek('-')) {
      index++;
    }
    if (index >= input.length()) {
      throw error("Invalid number");
    }
    if (peek('0')) {
      index++;
    } else if (input.charAt(index) >= '1' && input.charAt(index) <= '9') {
      while (index < input.length() && Character.isDigit(input.charAt(index))) {
        index++;
      }
    } else {
      throw error("Invalid number format");
    }
    boolean isDecimal = false;
    if (peek('.')) {
      isDecimal = true;
      index++;
      if (index >= input.length() || !Character.isDigit(input.charAt(index))) {
        throw error("Expected digit after decimal point");
      }
      while (index < input.length() && Character.isDigit(input.charAt(index))) {
        index++;
      }
    }
    if (peek('e') || peek('E')) {
      isDecimal = true;
      index++;
      if (peek('+') || peek('-')) {
        index++;
      }
      if (index >= input.length() || !Character.isDigit(input.charAt(index))) {
        throw error("Expected digit in exponent");
      }
      while (index < input.length() && Character.isDigit(input.charAt(index))) {
        index++;
      }
    }
    String raw = input.substring(start, index);
    if (!isDecimal) {
      try {
        return Long.parseLong(raw);
      } catch (NumberFormatException ignored) {
        return new BigDecimal(raw);
      }
    }
    return new BigDecimal(raw);
  }

  private void skipWhitespace() {
    while (index < input.length()) {
      char ch = input.charAt(index);
      if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
        index++;
      } else {
        break;
      }
    }
  }

  private boolean peek(char expected) {
    return index < input.length() && input.charAt(index) == expected;
  }

  private void expect(char expected) {
    if (index >= input.length() || input.charAt(index) != expected) {
      throw error("Expected '" + expected + "'");
    }
    index++;
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at position " + index);
  }
}
