package in.nirman.modules.payroll.api;

import in.nirman.modules.payroll.api.dto.PayrollDtos.OpenRunRequest;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayrollRunResponse;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayrollRunSummary;
import in.nirman.modules.payroll.api.dto.PayrollDtos.PayslipResponse;
import in.nirman.modules.payroll.api.dto.PayrollDtos.UpdatePayslipRequest;
import in.nirman.modules.payroll.api.dto.PayrollDtos.UpdateRunRequest;
import in.nirman.modules.payroll.service.PayrollService;
import in.nirman.modules.payroll.service.PayslipPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The month's payroll and the documents it produces.
 *
 * <p>Its own path rather than more endpoints under {@code /staff}, and behind its own two
 * permissions, because the two answer different questions to different people. {@code /staff}
 * administers people — where they live, who to telephone, what was agreed. This runs a month:
 * what each of them earned in it, what the statutes took, and what has to leave the bank.</p>
 */
@RestController
@RequestMapping("/api/v1/payroll")
@Tag(name = "Payroll", description = "Monthly payroll runs, payslips and the printed register")
public class PayrollController {

    private final PayrollService payroll;
    private final PayslipPdfService pdf;

    public PayrollController(PayrollService payroll, PayslipPdfService pdf) {
        this.payroll = payroll;
        this.pdf = pdf;
    }

    // ------------------------------------------------------------------ the month

    @GetMapping("/runs")
    @Operation(summary = "Every month that has been drawn, newest first")
    public List<PayrollRunSummary> runs() {
        return payroll.list();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a month and draw every payslip it can",
            description = "Refused if the month is already open — two payrolls for one month "
                    + "are two answers, and the employee has whichever was printed. Members "
                    + "with no salary structure on record are not drawn and are named in "
                    + "notDrawn rather than silently missing.")
    public PayrollRunResponse open(@Valid @RequestBody OpenRunRequest request) {
        return payroll.open(request);
    }

    @GetMapping("/runs/{id}")
    public PayrollRunResponse run(@PathVariable UUID id) {
        return payroll.get(id);
    }

    @PutMapping("/runs/{id}")
    @Operation(summary = "Correct the month. Changing the payable days redraws every slip.")
    public PayrollRunResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateRunRequest request) {
        return payroll.update(id, request);
    }

    @PostMapping("/runs/{id}/redraw")
    @Operation(summary = "Draw the month again against today's staff records",
            description = "Adds anybody now drawable and rebuilds the structure half of every "
                    + "slip. The typed figures — days, overtime, tax, recoveries — are carried "
                    + "across, because a redraw that reset them would not be used.")
    public PayrollRunResponse redraw(@PathVariable UUID id) {
        return payroll.redraw(id);
    }

    @PostMapping("/runs/{id}/finalise")
    @Operation(summary = "End the month. Once, and with no way back — those payslips exist.")
    public PayrollRunResponse finalise(@PathVariable UUID id) {
        return payroll.finalise(id);
    }

    @DeleteMapping("/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Throw away a draft month. A finalised one is refused.")
    public void delete(@PathVariable UUID id) {
        payroll.delete(id);
    }

    // ------------------------------------------------------------------ one slip

    @PutMapping("/payslips/{id}")
    @Operation(summary = "The figures no rule can know: days present, overtime hours, tax and "
            + "recoveries. Everything else on the slip is recomputed from them.")
    public PayslipResponse updatePayslip(@PathVariable UUID id,
                                         @Valid @RequestBody UpdatePayslipRequest request) {
        return payroll.updatePayslip(id, request);
    }

    @DeleteMapping("/payslips/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take somebody out of the month",
            description = "Really removed rather than zeroed: a slip of zeroes says he was "
                    + "paid nothing, which is a different claim from his not belonging in the "
                    + "month.")
    public void removePayslip(@PathVariable UUID id) {
        payroll.removePayslip(id);
    }

    @GetMapping("/members/{userId}/payslips")
    @Operation(summary = "One member's payslips, newest month first")
    public List<PayslipResponse> forMember(@PathVariable UUID userId) {
        return payroll.forMember(userId);
    }

    // ------------------------------------------------------------------ the paper

    @GetMapping("/runs/{id}/register.pdf")
    @Operation(summary = "The month on one landscape sheet — the office's document",
            description = "Carries the employer's own contributions, which the employee's "
                    + "payslip deliberately does not.")
    public ResponseEntity<byte[]> register(@PathVariable UUID id) {
        return asPdf(pdf.register(id));
    }

    @GetMapping("/runs/{id}/payslips.pdf")
    @Operation(summary = "Every payslip in the run, one to a page")
    public ResponseEntity<byte[]> allPayslips(@PathVariable UUID id) {
        return asPdf(pdf.allPayslips(id));
    }

    @GetMapping("/payslips/{id}/pdf")
    @Operation(summary = "One member's payslip, as it is handed to him")
    public ResponseEntity<byte[]> payslip(@PathVariable UUID id) {
        return asPdf(pdf.payslip(id));
    }

    private static ResponseEntity<byte[]> asPdf(PayslipPdfService.Rendered rendered) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rendered.fileName() + "\"")
                .body(rendered.body());
    }
}
