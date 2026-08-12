package in.nirman.modules.identity.api;

import in.nirman.modules.identity.api.dto.StaffDtos.RecordSalaryRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.SalaryRevisionResponse;
import in.nirman.modules.identity.api.dto.StaffDtos.SaveStaffProfileRequest;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffDashboardResponse;
import in.nirman.modules.identity.api.dto.StaffDtos.StaffProfileResponse;
import in.nirman.modules.identity.service.StaffRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff records: what an employer holds about the people on its payroll.
 *
 * <p>Its own path rather than more endpoints under {@code /users}, because the two are
 * different things behind different permissions. {@code /users} administers logins — who may
 * sign in and what they may do. This administers people — where they live, who to telephone,
 * what was agreed about their pay. An administrator holds both today; the split is what lets
 * an office manager hold the second without the first.</p>
 */
@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "Staff", description = "Employee records, employment terms and salary history")
public class StaffController {

    private final StaffRecordService staff;

    public StaffController(StaffRecordService staff) {
        this.staff = staff;
    }

    @GetMapping
    @Operation(summary = "Every member, filled in or not — a blank record is a record to fill")
    public List<StaffProfileResponse> list() {
        return staff.list();
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Headcount by footing, this month's payroll, and who needs attention")
    public StaffDashboardResponse dashboard() {
        return staff.dashboard();
    }

    @GetMapping("/{userId}")
    public StaffProfileResponse get(@PathVariable UUID userId) {
        return staff.get(userId);
    }

    @PutMapping("/{userId}")
    @Operation(summary = "Save the record whole. The version is null the first time and "
            + "required afterwards.")
    public StaffProfileResponse save(@PathVariable UUID userId,
                                     @Valid @RequestBody SaveStaffProfileRequest request) {
        return staff.save(userId, request);
    }

    @GetMapping("/{userId}/salary")
    @Operation(summary = "What they have been paid, newest first")
    public List<SalaryRevisionResponse> salaryHistory(@PathVariable UUID userId) {
        return staff.salaryHistory(userId);
    }

    @PostMapping("/{userId}/salary")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a change of pay from a date. Appended, never edited: a raise "
            + "in April must not rewrite what March cost.")
    public SalaryRevisionResponse recordSalary(@PathVariable UUID userId,
                                               @Valid @RequestBody RecordSalaryRequest request) {
        return staff.recordSalary(userId, request);
    }
}
