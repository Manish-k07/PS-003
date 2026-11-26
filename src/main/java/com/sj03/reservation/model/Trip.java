package com.sj03.reservation.model;

/**
 * Validated half-open journey interval [start, end) over route edges.
 */
public record Trip(int start, int end, long segmentMask) {
  /** Constructs a Trip for station indices start &lt; end (with up to 64 route edges). */
  public static Trip of(int start, int end) {
    if (start < 0 || end <= start || end > 63) {
      throw new IllegalArgumentException("Invalid station edge range [" + start + ", " + end + ")");
    }
    long mask = ((1L << (end - start)) - 1L) << start;
    return new Trip(start, end, mask);
  }

  /** Returns true if this journey shares at least one route edge with other. */
  public boolean overlaps(Trip other) {
    return (this.segmentMask & other.segmentMask) != 0L;
  }

  /** Returns the edge indices occupied by this trip. */
  public int[] segments() {
    int[] result = new int[end - start];
    for (int i = 0; i < result.length; i++) {
      result[i] = start + i;
    }
    return result;
  }
}
