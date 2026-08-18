package in.nirman.modules.treasury.api;

import in.nirman.modules.treasury.api.dto.BankDepositDtos.AddPhotoRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.CloseDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.CreateDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.DepositResponse;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.RegisterResponse;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.ReopenDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.UpdateDepositRequest;
import in.nirman.modules.treasury.service.BankDepositService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * The register of fixed deposits, across every contract and none.
 *
 * <p>Closing is its own endpoint rather than a field on the update, as lodging and releasing are
 * on a contract's deposits: the bank paying out is an event with a date, and it is refused while
 * a department is still holding the certificate.</p>
 */
@RestController
@RequestMapping("/api/v1/bank-deposits")
@Tag(name = "Fixed deposits", description = "Every FDR the company holds, and what each is pledged against")
public class BankDepositController {

    private final BankDepositService deposits;

    public BankDepositController(BankDepositService deposits) {
        this.deposits = deposits;
    }

    @GetMapping
    @Operation(summary = "The whole register, with what is pledged, what is idle and what matures soon")
    public RegisterResponse register() {
        return deposits.register();
    }

    @GetMapping("/{id}")
    @Operation(summary = "One certificate, its pledges and its photographs")
    public DepositResponse get(@PathVariable UUID id) {
        return deposits.get(id);
    }

    @PostMapping
    @Operation(summary = "Enter a fixed deposit the company has bought")
    public ResponseEntity<DepositResponse> create(@Valid @RequestBody CreateDepositRequest request) {
        DepositResponse created = deposits.create(request);
        return ResponseEntity.created(URI.create("/api/v1/bank-deposits/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct what the certificate says")
    public DepositResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateDepositRequest request) {
        return deposits.update(id, request);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "The bank has paid it out")
    public DepositResponse close(@PathVariable UUID id,
                                 @Valid @RequestBody CloseDepositRequest request) {
        return deposits.close(id, request);
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Undo a closing entered against the wrong certificate")
    public DepositResponse reopen(@PathVariable UUID id,
                                  @Valid @RequestBody ReopenDepositRequest request) {
        return deposits.reopen(id, request);
    }

    @PostMapping("/{id}/photos")
    @Operation(summary = "Link an uploaded photograph of the certificate")
    public DepositResponse addPhoto(@PathVariable UUID id,
                                    @Valid @RequestBody AddPhotoRequest request) {
        return deposits.addPhoto(id, request);
    }

    @DeleteMapping("/{id}/photos/{attachmentId}")
    @Operation(summary = "Unlink a photograph")
    public DepositResponse removePhoto(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        return deposits.removePhoto(id, attachmentId);
    }
}
