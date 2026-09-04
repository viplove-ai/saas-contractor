package in.nirman.modules.dpr.repository;

import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.domain.DprPhoto;

/** One photograph with the report it was taken for, as the gallery query returns them. */
public record PhotoOnReport(DprPhoto photo, DailyProgressReport report) {
}
