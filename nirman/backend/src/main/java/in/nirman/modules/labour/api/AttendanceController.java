package in.nirman.modules.labour.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.AttendanceResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.BulkAttendanceResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.CorrectAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.LockPeriodRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.RosterResponse;
import in.nirman.modules.labour.api.dto.AttendanceDtos.SubmitAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.UpdateAttendanceRequest;
import in.nirman.modules.labour.api.dto.AttendanceDtos.VerifyAttendanceRequest;
import in.nirman.modules.labour.domain.WorkflowStatus;
import in.nirman.modules.labour.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Attendance", description = "Muster roll, bulk entry, verification and period lock")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/roster")
    @Operation(summary = "The muster roll for a site and day, with anything already marked prefilled")
    public RosterResponse roster(@RequestParam UUID siteId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.roster(siteId, date);
    }

    @PostMapping("/bulk")
    @Operation(summary = "Save a batch of marks. Client-generated ids make a re-send idempotent.")
    public BulkAttendanceResponse saveBulk(@Valid @RequestBody BulkAttendanceRequest request) {
        return attendanceService.saveBulk(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit one record. Draft and rejected rows only.")
    public AttendanceResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateAttendanceRequest request) {
        return attendanceService.update(id, request);
    }

    @PostMapping("/{id}/correct")
    @Operation(summary = "Amend a verified row. Needs a reason; posts the difference to the worker's ledger.")
    public AttendanceResponse correct(@PathVariable UUID id,
                                      @Valid @RequestBody CorrectAttendanceRequest request) {
        return attendanceService.correct(id, request);
    }

    @PostMapping("/submit")
    @Operation(summary = "Send the day's drafts for engineer verification")
    public Map<String, Object> submit(@Valid @RequestBody SubmitAttendanceRequest request) {
        int submitted = attendanceService.submit(request.siteId(), request.date());
        return Map.of("submitted", submitted);
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify or reject. Verifying freezes the wage and posts to the worker's ledger.")
    public Map<String, Object> verify(@Valid @RequestBody VerifyAttendanceRequest request) {
        int affected = attendanceService.verify(request);
        return Map.of("affected", affected, "action", request.action());
    }

    @PostMapping("/lock")
    @Operation(summary = "Close a month for a site. Verified rows become locked and immovable.")
    public AttendanceService.LockResult lock(@Valid @RequestBody LockPeriodRequest request) {
        return attendanceService.lockPeriod(request.siteId(), YearMonth.parse(request.yearMonth()));
    }

    @GetMapping
    @Operation(summary = "Attendance rows in a date range, narrowed to the caller's sites")
    public PageResponse<AttendanceResponse> list(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID workerId,
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return attendanceService.list(siteId, workerId, status, from, to,
                PageRequest.of(page, Math.min(size, 500),
                        Sort.by(Sort.Direction.DESC, "attendanceDate")));
    }
}
