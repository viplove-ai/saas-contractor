package in.nirman.modules.billing.api;

import in.nirman.modules.billing.api.dto.BillingDtos.CreateSheetRequest;
import in.nirman.modules.billing.api.dto.BillingDtos.SheetResponse;
import in.nirman.modules.billing.api.dto.BillingDtos.UpdateSheetRequest;
import in.nirman.modules.billing.service.MeasurementService;
import in.nirman.modules.billing.service.MeasurementSheetPdfService;
import in.nirman.modules.billing.service.SheetGeometry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/measurement-sheets")
@Tag(name = "Billing", description = "Measurement sheets and running account bills")
public class MeasurementController {

    private final MeasurementService service;
    private final MeasurementSheetPdfService blankSheets;

    public MeasurementController(MeasurementService service,
                                 MeasurementSheetPdfService blankSheets) {
        this.service = service;
        this.blankSheets = blankSheets;
    }

    /**
     * Blank master sheets to print, bind and take to the site.
     *
     * <p>Generic — one design for every tender. The serials are what the register's duplicate
     * guard matches on, so a run is printed once and never reprinted with the same numbers.</p>
     */
    @GetMapping(value = "/blank", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Printable blank measurement sheets, serially numbered")
    public ResponseEntity<byte[]> blank(@RequestParam(defaultValue = "1") int from,
                                        @RequestParam(defaultValue = "20") int count) {
        byte[] pdf = blankSheets.render(from, count);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"measurement-sheets-%06d.pdf\"".formatted(from))
                .body(pdf);
    }

    @GetMapping
    @Operation(summary = "Measurement sheets on a project, optionally one item's or only the unbilled")
    public List<SheetResponse> list(@RequestParam UUID projectId,
                                    @RequestParam(required = false) UUID boqItemId,
                                    @RequestParam(required = false) Boolean billed) {
        return service.list(projectId, boqItemId, billed);
    }

    @PostMapping
    @Operation(summary = "Record a measurement sheet, as a draft")
    public ResponseEntity<SheetResponse> create(@Valid @RequestBody CreateSheetRequest request) {
        SheetResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/measurement-sheets/" + created.id()))
                .body(created);
    }

    /**
     * Where every box sits on the printed page, in millimetres.
     *
     * <p>Served rather than duplicated in the client. The reader corrects a photograph onto
     * these coordinates and then reads each box by arithmetic instead of searching for it —
     * which is the whole reason recognising this page is tractable at all. A layout defined in
     * two places is a reader that silently starts on the wrong column the first time somebody
     * nudges a margin.</p>
     */
    @GetMapping("/next-serial")
    @Operation(summary = "Where the next run of blank sheets should start")
    public MeasurementSheetPdfService.SerialSuggestion nextSerial() {
        return blankSheets.nextSerial();
    }

    @GetMapping("/geometry")
    @Operation(summary = "The printed sheet's box positions, shared by the printer and the reader")
    public SheetGeometryResponse geometry() {
        return new SheetGeometryResponse(SheetGeometry.PAGE_WIDTH_MM, SheetGeometry.PAGE_HEIGHT_MM,
                SheetGeometry.MARK_INSET_MM, SheetGeometry.MARK_SIZE_MM,
                SheetGeometry.MARK_WIDE_SIZE_MM, SheetGeometry.ROWS,
                SheetGeometry.FIELDS.stream()
                        .map(f -> new FieldResponse(f.name(), f.digits(), f.decimals()))
                        .toList(),
                SheetGeometry.boxes());
    }

    public record FieldResponse(String name, int digits, int decimals) {
    }

    public record SheetGeometryResponse(
            double pageWidthMm,
            double pageHeightMm,
            double markInsetMm,
            double markSizeMm,
            double markWideSizeMm,
            int rows,
            List<FieldResponse> fields,
            List<SheetGeometry.Box> boxes) {
    }

    @GetMapping("/{id}")
    public SheetResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Amend a draft sheet. A signed one is corrected by a new sheet, never edited")
    public SheetResponse update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateSheetRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/sign")
    @Operation(summary = "Sign the sheet. Refused while the written and computed totals disagree")
    public SheetResponse sign(@PathVariable UUID id) {
        return service.sign(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
