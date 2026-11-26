package com.sj03.reservation.model;

import java.util.List;

/**
 * Reservation booking ledger containing 1 to 6 passengers on a shared journey, class, and quota.
 */
public final class Booking {
  private final String id;
  private final String classCode;
  private final String quota;
  private final Trip trip;
  private final String from;
  private final String to;
  private final String at;
  private final List<Passenger> passengers;

  private int totalPaid;
  private int currentFare;
  private int refunded;
  private boolean canceled;

  public Booking(
      String id,
      String classCode,
      String quota,
      Trip trip,
      String from,
      String to,
      String at,
      List<Passenger> passengers) {
    this.id = id;
    this.classCode = classCode;
    this.quota = quota;
    this.trip = trip;
    this.from = from;
    this.to = to;
    this.at = at;
    this.passengers = List.copyOf(passengers);
  }

  public String id() { return id; }
  public String classCode() { return classCode; }
  public String quota() { return quota; }
  public Trip trip() { return trip; }
  public String from() { return from; }
  public String to() { return to; }
  public String at() { return at; }
  public List<Passenger> passengers() { return passengers; }

  public int totalPaid() { return totalPaid; }
  public void setTotalPaid(int totalPaid) { this.totalPaid = totalPaid; }

  public int currentFare() { return currentFare; }
  public void setCurrentFare(int currentFare) { this.currentFare = currentFare; }

  public int refunded() { return refunded; }
  public void addRefunded(int delta) { this.refunded += delta; }

  public boolean isCanceled() { return canceled; }
  public void setCanceled(boolean canceled) { this.canceled = canceled; }
}
