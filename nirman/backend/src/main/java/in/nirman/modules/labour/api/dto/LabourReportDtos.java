package in.nirman.modules.labour.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only projections for the labour reports. Nothing here maps to a table. */
public final class LabourReportDtos {

    private LabourReportDtos() {
    }

    /**
     * The muster register as it is kept on paper: one row per worker, one column per day,
     * a single letter in each cell. Deliberately carries no money — the permission matrix
     * gives every field role operational reporting but reserves financial reporting for the
     * administrator and the accountant, and this is the version a supervisor may see.
     */
    public record AttendanceRegisterReport(
            UUID siteId,
            String siteName,
            LocalDate from,
            LocalDate to,
            List<LocalDate> days,
            List<RegisterRow> rows) {

        /**
         * @param marks day → one of P, H, A, L, or absent from the map when unmarked
         */
        public record RegisterRow(
                UUID workerId,
                String workerCode,
                String workerName,
                Map<LocalDate, String> marks,
                long presentDays,
                long halfDays,
                long absentDays,
                long leaveDays,
                BigDecimal regularHours,
                BigDecimal overtimeHours) {
        }
    }

    /**
     * The money view, and the one the field sheet ends in:
     * {@code Total Amount − Advance = Balance Payment}.
     */
    public record WageSummaryReport(
            UUID siteId,
            String siteName,
            LocalDate from,
            LocalDate to,
            List<WageRow> rows,
            WageTotals totals) {

        public record WageRow(
                UUID workerId,
                String workerCode,
                String workerName,
                long presentDays,
                long halfDays,
                BigDecimal regularHours,
                BigDecimal overtimeHours,
                BigDecimal wageAmount,
                BigDecimal overtimeAmount,
                BigDecimal totalEarned,
                BigDecimal advanceDrawn,
                BigDecimal netPayable) {
        }

        public record WageTotals(
                int workers,
                BigDecimal regularHours,
                BigDecimal overtimeHours,
                BigDecimal totalEarned,
                BigDecimal advanceDrawn,
                BigDecimal netPayable) {
        }
    }
}
