package in.nirman.modules.labour.domain;

/** What the worker did that day. Drives the wage calculation entirely. */
public enum AttendanceStatus {
    PRESENT,
    /** Half a day's wage and never any overtime, whatever the clock says (assumption 8). */
    HALF_DAY,
    ABSENT,
    LEAVE;

    /** Absent and leave earn nothing — paid leave is out of scope for v1. */
    public boolean isPaid() {
        return this == PRESENT || this == HALF_DAY;
    }
}
