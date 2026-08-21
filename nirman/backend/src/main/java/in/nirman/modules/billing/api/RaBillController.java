package in.nirman.modules.billing.api;

import in.nirman.modules.billing.api.dto.BillingDtos.AgreementRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.AgreementResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.BillResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.BillSummary;
import in.nirman.modules.billing.api.dto.BillingDtos.CreateBillRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.DecideBillRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.UnbilledSummary;
import in.nirman.modules.billing.service.AgreementService;
import in.nirman.modules.billing.service.RaBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Billing", description = "Measurement sheets and running account bills")
public class RaBillController {

    private final RaBillService bills;
    private final AgreementService agreements;

    public RaBillController(RaBillService bills, AgreementService agreements) {
        this.bills = bills;
        this.agreements = agreements;
    }

    // ------------------------------------------------------------------ the agreement

    @GetMapping("/projects/{projectId}/agreement")
    @Operation(summary = "The tender's details, asked once at its first bill")
    public ResponseEntity<AgreementResponse> agreement(@PathVariable UUID projectId) {
        return agreements.find(projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/projects/{projectId}/agreement")
    @Operation(summary = "Record or amend the tender's details and its rate adjustments")
    public AgreementResponse saveAgreement(@PathVariable UUID projectId,
                                           @Valid @RequestBody AgreementRequest request) {
        return agreements.save(projectId, request);
    }

    // ------------------------------------------------------------------ the unbilled queue

    @GetMapping("/projects/{projectId}/unbilled")
    @Operation(summary = "Measured work no bill has claimed — what the next bill will sweep")
    public UnbilledSummary unbilled(
            @PathVariable UUID projectId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff) {
        return bills.unbilled(projectId, cutoff);
    }

    // ------------------------------------------------------------------ bills

    @GetMapping("/ra-bills")
    public List<BillSummary> list(@RequestParam UUID projectId) {
        return bills.list(projectId);
    }

    @PostMapping("/ra-bills")
    @Operation(summary = "Open the next bill in the series and claim everything measured up to its cutoff")
    public ResponseEntity<BillResponse> create(@Valid @RequestBody CreateBillRequest request) {
        BillResponse created = bills.create(request);
        return ResponseEntity.created(URI.create("/api/v1/ra-bills/" + created.id())).body(created);
    }

    @GetMapping("/ra-bills/{id}")
    public BillResponse get(@PathVariable UUID id) {
        return bills.get(id);
    }

    @PostMapping("/ra-bills/{id}/decide")
    @Operation(summary = "Submit, check, pass or send back. Passing freezes the figures")
    public BillResponse decide(@PathVariable UUID id,
                               @Valid @RequestBody DecideBillRequest request) {
        return bills.decide(id, request);
    }

    @DeleteMapping("/ra-bills/{id}")
    @Operation(summary = "Discard a bill that has not been passed, returning its sheets to the queue")
    public ResponseEntity<Void> discard(@PathVariable UUID id) {
        bills.discard(id);
        return ResponseEntity.noContent().build();
    }
}
