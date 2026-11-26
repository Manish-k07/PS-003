package com.sj03.reservation.model;

/**
 * Mutable state of an individual passenger inside a reservation booking.
 */
public final class Passenger {
  private final String id;
  private final String bookingId;
  private final int order;
  private final String name;
  private final int age;
  private final String gender;
  private final BerthType berthPreference;
  private final String from;
  private final String to;
  private final Trip trip;
  private final String classCode;
  private final String quota;

  private PassengerStatus status;
  private String berthId;
  private String coachId;
  private RacPair racPair;
  private Allocation allocation;
  private int fare;
  private int paidFare;
  private int refund;
  private int previousRefund;
  private String cancelledAt;

  public Passenger(
      String id,
      String bookingId,
      int order,
      String name,
      int age,
      String gender,
      BerthType berthPreference,
      String from,
      String to,
      Trip trip,
      String classCode,
      String quota,
      PassengerStatus status) {
    this.id = id;
    this.bookingId = bookingId;
    this.order = order;
    this.name = name;
    this.age = age;
    this.gender = gender;
    this.berthPreference = berthPreference;
    this.from = from;
    this.to = to;
    this.trip = trip;
    this.classCode = classCode;
    this.quota = quota;
    this.status = status;
  }

  public String id() { return id; }
  public String bookingId() { return bookingId; }
  public int order() { return order; }
  public String name() { return name; }
  public int age() { return age; }
  public String gender() { return gender; }
  public BerthType berthPreference() { return berthPreference; }
  public String from() { return from; }
  public String to() { return to; }
  public Trip trip() { return trip; }
  public String classCode() { return classCode; }
  public String quota() { return quota; }

  public PassengerStatus status() { return status; }
  public void setStatus(PassengerStatus status) { this.status = status; }

  public String berthId() { return berthId; }
  public void setBerthId(String berthId) { this.berthId = berthId; }

  public String coachId() { return coachId; }
  public void setCoachId(String coachId) { this.coachId = coachId; }

  public RacPair racPair() { return racPair; }
  public void setRacPair(RacPair racPair) { this.racPair = racPair; }

  public Allocation allocation() { return allocation; }
  public void setAllocation(Allocation allocation) { this.allocation = allocation; }

  public int fare() { return fare; }
  public void setFare(int fare) { this.fare = fare; }

  public int paidFare() { return paidFare; }
  public void setPaidFare(int paidFare) { this.paidFare = paidFare; }

  public int refund() { return refund; }
  public void addRefund(int amount) { this.refund += amount; }

  public int previousRefund() { return previousRefund; }
  public void setPreviousRefund(int previousRefund) { this.previousRefund = previousRefund; }

  public String cancelledAt() { return cancelledAt; }
  public void setCancelledAt(String cancelledAt) { this.cancelledAt = cancelledAt; }

  public boolean isInfant() { return age < 5; }
  public boolean isChild() { return age < 12; }
  public boolean isAdult() { return age >= 18; }
  public boolean isActive() { return status != PassengerStatus.CANCELLED; }

  /** BR-02 lower-berth priority: men 60+ or women 45+. */
  public boolean isPriorityLower() {
    return ("M".equals(gender) && age >= 60) || ("F".equals(gender) && age >= 45);
  }

  /** BR-05 senior quota/concession eligibility: men 60+ or women 58+. */
  public boolean isEligibleSenior() {
    return ("M".equals(gender) && age >= 60) || ("F".equals(gender) && age >= 58);
  }
}
