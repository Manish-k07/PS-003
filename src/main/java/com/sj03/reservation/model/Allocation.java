package com.sj03.reservation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Active physical berth allocation either for one CNF passenger or one shared RAC pair.
 */
public final class Allocation {
  public enum Type {
    CNF,
    RAC
  }

  private final Type type;
  private final List<Passenger> members;
  private long segmentMask;

  public Allocation(Type type, List<Passenger> members, long segmentMask) {
    this.type = type;
    this.members = new ArrayList<>(members);
    this.segmentMask = segmentMask;
  }

  public Type type() {
    return type;
  }

  public List<Passenger> members() {
    return members;
  }

  public long segmentMask() {
    return segmentMask;
  }

  public void setSegmentMask(long segmentMask) {
    this.segmentMask = segmentMask;
  }
}
