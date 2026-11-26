package com.sj03.reservation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical berth with O(1) bitmask segment availability and an auditable allocation ledger.
 */
public final class Berth {
  private final String id;
  private final String coachId;
  private final String classCode;
  private final BerthType type;
  private final int[] edgeOccupancy;
  private final List<Allocation> allocations = new ArrayList<>();
  private long occupiedMask = 0L;

  public Berth(String id, String coachId, String classCode, BerthType type, int edgeCount) {
    this.id = id;
    this.coachId = coachId;
    this.classCode = classCode;
    this.type = type;
    this.edgeOccupancy = new int[edgeCount];
  }

  public String id() {
    return id;
  }

  public String coachId() {
    return coachId;
  }

  public String classCode() {
    return classCode;
  }

  public BerthType type() {
    return type;
  }

  public List<Allocation> allocations() {
    return allocations;
  }

  public int[] edgeOccupancy() {
    return edgeOccupancy;
  }

  public long occupiedMask() {
    return occupiedMask;
  }

  /** Checks in O(1) whether every route edge in candidateMask is unoccupied. */
  public boolean fits(long candidateMask) {
    return (occupiedMask & candidateMask) == 0L;
  }

  /**
   * Checks whether candidateMask conflicts with any allocation on this berth other than ignored.
   * Used when checking if an open RAC pair can absorb another passenger without colliding with
   * other CNF allocations on non-overlapping segments of the same side-lower berth.
   */
  public boolean fitsIgnoring(long candidateMask, Allocation ignored) {
    if (ignored == null) {
      return fits(candidateMask);
    }
    long otherMask = 0L;
    for (Allocation allocation : allocations) {
      if (allocation != ignored) {
        otherMask |= allocation.segmentMask();
      }
    }
    return (otherMask & candidateMask) == 0L;
  }

  public void addAllocation(Allocation allocation) {
    allocations.add(allocation);
    applyDelta(allocation.segmentMask(), +1);
  }

  public void removeAllocation(Allocation allocation) {
    if (allocations.remove(allocation)) {
      applyDelta(allocation.segmentMask(), -1);
    }
  }

  public void updateAllocationMask(Allocation allocation, long newMask) {
    long oldMask = allocation.segmentMask();
    if (oldMask == newMask) {
      return;
    }
    applyDelta(oldMask, -1);
    allocation.setSegmentMask(newMask);
    applyDelta(newMask, +1);
  }

  private void applyDelta(long mask, int delta) {
    for (int edge = 0; edge < edgeOccupancy.length; edge++) {
      if ((mask & (1L << edge)) != 0L) {
        edgeOccupancy[edge] += delta;
        if (edgeOccupancy[edge] > 0) {
          occupiedMask |= (1L << edge);
        } else {
          occupiedMask &= ~(1L << edge);
        }
      }
    }
  }
}
