package com.sj03.reservation.json;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Deterministic compact JSON serializer for Java maps, lists, and primitive values.
 */
public final class JsonWriter {
  private JsonWriter() {}

  /** Serializes a supported Java value into compact JSON. */
  public static String stringify(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeString(sb, s);
    } else if (value instanceof Boolean b) {
      sb.append(b.booleanValue() ? "true" : "false");
    } else if (value instanceof BigDecimal bd) {
      sb.append(bd.stripTrailingZeros().toPlainString());
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, String.valueOf(entry.getKey()));
        sb.append(':');
        writeValue(sb, entry.getValue());
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      sb.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        writeValue(sb, list.get(i));
      }
      sb.append(']');
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    sb.append('"');
  }
}
