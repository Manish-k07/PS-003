package com.sj03.reservation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared side-lower berth slot occupied by up to two RAC passengers.
 */
public final class RacPair {
  private final String id;
  private final String classCode;
  private final String berthId;
  private final List<Passenger> members = new ArrayList<>(2);
  private Allocation allocation;
  private long segmentMask;
  private boolean open = true;

  public RacPair(String id, String classCode, String berthId) {
    this.id = id;
    this.classCode = classCode;
    this.berthId = berthId;
  }

  public String id() {
    return id;
  }

  public String classCode() {
    return classCode;
  }

  public String berthId() {
    return berthId;
  }

  public List<Passenger> members() {
    return members;
  }

  public Allocation allocation() {
    return allocation;
  }

  public void setAllocation(Allocation allocation) {
    this.allocation = allocation;
  }

  public long segmentMask() {
    return segmentMask;
  }

  public void setSegmentMask(long segmentMask) {
    this.segmentMask = segmentMask;
  }

  public boolean isOpen() {
    return open;
  }

  public void setOpen(boolean open) {
    this.open = open;
  }
}
