package in.nirman.modules.dashboard.api;

import in.nirman.modules.dashboard.api.dto.DashboardDtos.CompanyDashboard;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.DataQualityDashboard;
import in.nirman.modules.dashboard.api.dto.DashboardDtos.SiteDashboard;
import in.nirman.modules.dashboard.service.DashboardService;
import in.nirman.modules.dashboard.service.DataQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The three dashboards docs/05 names. Each takes a period; unspecified means the month to date,
 * which is the window somebody opening a dashboard almost always means.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboards", description = "Company, site and data-quality views over the other modules")
public class DashboardController {

    private final DashboardService dashboards;
    private final DataQualityService dataQuality;

    public DashboardController(DashboardService dashboards, DataQualityService dataQuality) {
        this.dashboards = dashboards;
        this.dataQuality = dataQuality;
    }

    @GetMapping("/admin")
    @Operation(summary = "The firm over a period. Material appears as received, consumed and held — three figures that add up.")
    public CompanyDashboard company(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.company(orDefaultFrom(from), orDefaultTo(to));
    }

    @GetMapping("/site/{siteId}")
    @Operation(summary = "One site: labour, material, cash, contract progress and the state of its reports")
    public SiteDashboard site(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.site(siteId, orDefaultFrom(from), orDefaultTo(to));
    }

    @GetMapping("/data-quality")
    @Operation(summary = "What is missing or unfinished in the records, each finding with what to do about it")
    public DataQualityDashboard dataQuality(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dataQuality.dataQuality(siteId, orDefaultFrom(from), orDefaultTo(to));
    }

    /** The first of the current month. */
    private static LocalDate orDefaultFrom(LocalDate from) {
        return from != null ? from : LocalDate.now().withDayOfMonth(1);
    }

    private static LocalDate orDefaultTo(LocalDate to) {
        return to != null ? to : LocalDate.now();
    }
}
