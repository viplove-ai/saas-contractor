package in.nirman.modules.labour.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.labour.api.dto.AdvanceDtos.CreatePaymentRequest;
import in.nirman.modules.labour.api.dto.AdvanceDtos.PaymentResponse;
import in.nirman.modules.labour.service.WorkerPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Worker payments", description = "Wages handed over on a payday, against the ledger")
public class WorkerPaymentController {

    private final WorkerPaymentService paymentService;

    public WorkerPaymentController(WorkerPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/worker-payments")
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return paymentService.list(siteId, workerId, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "paymentDate")));
    }

    @PostMapping("/worker-payments")
    @Operation(summary = "Pay wages. Posts to the ledger and recovers his open advances; "
            + "refused past what he is owed. Idempotent on the client id.")
    public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse created = paymentService.pay(request);
        return ResponseEntity.created(URI.create("/api/v1/worker-payments/" + created.id()))
                .body(created);
    }
}
