package in.nirman.modules.treasury.api;

import in.nirman.modules.treasury.api.dto.TreasuryDtos.TreasuryDashboard;
import in.nirman.modules.treasury.service.TreasuryDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/treasury")
@Tag(name = "Treasury", description = "The company's blocked money, across every contract")
public class TreasuryDashboardController {

    private final TreasuryDashboardService dashboard;

    public TreasuryDashboardController(TreasuryDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * @param asOf the day every countdown is measured from. Defaults to today; it exists so the
     *             screen can be read as of a month end without the office doing date arithmetic
     *             in its head.
     */
    @GetMapping("/dashboard")
    @Operation(summary = "Deposits and guarantees across every contract: what is out, what comes back when")
    public TreasuryDashboard get(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return dashboard.build(asOf);
    }
}
