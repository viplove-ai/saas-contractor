package in.nirman.modules.dpr.service;

import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.repository.DailyProgressReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DprLookupService implements DprLookup {

    private final DailyProgressReportRepository reports;

    public DprLookupService(DailyProgressReportRepository reports) {
        this.reports = reports;
    }

    @Override
    public boolean approvedOn(UUID siteId, LocalDate date) {
        return reports.findBySiteIdAndReportDateAndDeletedAtIsNull(siteId, date)
                .map(report -> report.getWorkflowStatus() == DailyProgressReport.Workflow.APPROVED)
                .orElse(false);
    }
}
