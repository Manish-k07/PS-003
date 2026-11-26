package com.sj03.reservation.model;

import java.util.Locale;

/**
 * Physical berth positions in railway coaches.
 */
public enum BerthType {
  LOWER,
  MIDDLE,
  UPPER,
  SIDE_LOWER,
  SIDE_UPPER;

  /** Parses a berth type name strictly, rejecting unknown types. */
  public static BerthType parseRequired(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Berth type cannot be blank");
    }
    try {
      return BerthType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown berth type: " + raw);
    }
  }

  /** Parses an optional passenger berth preference; returns null when absent or unrecognized. */
  public static BerthType parseOptionalPreference(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return BerthType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
