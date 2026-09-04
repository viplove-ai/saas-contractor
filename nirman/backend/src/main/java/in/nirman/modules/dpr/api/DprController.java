package in.nirman.modules.dpr.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.AttachPhotoRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.SetPlantRatesRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.CreateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.DeleteDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.DprPrefill;
import in.nirman.modules.dpr.api.dto.DprDtos.DprResponse;
import in.nirman.modules.dpr.api.dto.DprDtos.UpdateDprRequest;
import in.nirman.modules.dpr.api.dto.DprDtos.VerifyDprRequest;
import in.nirman.modules.dpr.domain.DailyProgressReport;
import in.nirman.modules.dpr.api.dto.DprDtos.GalleryPhotoResponse;
import in.nirman.modules.dpr.service.DprGalleryService;
import in.nirman.modules.dpr.service.DprPdfService;
import in.nirman.modules.dpr.service.DprPrefillService;
import in.nirman.modules.dpr.service.DprService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dprs")
@Tag(name = "DPR", description = "Daily progress reports: prefill, draft, verify and print")
public class DprController {

    private final DprService dprs;
    private final DprPrefillService prefill;
    private final DprPdfService pdf;
    private final DprGalleryService gallery;

    public DprController(DprService dprs, DprPrefillService prefill, DprPdfService pdf,
                         DprGalleryService gallery) {
        this.dprs = dprs;
        this.prefill = prefill;
        this.pdf = pdf;
        this.gallery = gallery;
    }

    @GetMapping
    @Operation(summary = "Reports, narrowed to the caller's sites")
    public PageResponse<DprResponse> list(
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) DailyProgressReport.Workflow status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return dprs.list(siteId, status, from, to,
                PageRequest.of(page, Math.min(size, 200),
                        Sort.by(Sort.Direction.DESC, "reportDate")));
    }

    /**
     * Literal path, so it is matched before {@code /{id}}: Spring prefers the more specific
     * pattern, and "photos" is not a UUID in any case.
     */
    @GetMapping("/photos")
    @Operation(summary = "A project's photographs, read off its daily reports, newest day first")
    public PageResponse<GalleryPhotoResponse> gallery(
            @RequestParam UUID projectId,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "60") int size) {
        return gallery.gallery(projectId, siteId, from, to, PageRequest.of(page, Math.min(size, 200)));
    }

    @GetMapping("/prefill")
    @Operation(summary = "What labour, inventory and expense already know about the day, derived live from the records")
    public DprPrefill prefill(
            @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return prefill.prefill(siteId, date);
    }

    @PostMapping
    @Operation(summary = "Open the day's report. One per site per day; 409 names the one that already covers it.")
    public ResponseEntity<DprResponse> create(@Valid @RequestBody CreateDprRequest request) {
        DprResponse created = dprs.create(request);
        return ResponseEntity.created(URI.create("/api/v1/dprs/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public DprResponse get(@PathVariable UUID id) {
        return dprs.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit. Draft and returned reports only — a verified report is the document that was signed.")
    public DprResponse update(@PathVariable UUID id,
                              @Valid @RequestBody UpdateDprRequest request) {
        return dprs.update(id, request);
    }

    /**
     * A body on a DELETE, which is unusual and deliberate — the reason is required, and a
     * query string leaves it in every access log. The same shape the project register uses.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a draft or returned report, freeing its day. Refused once it has been sent.")
    public DprResponse delete(@PathVariable UUID id, @Valid @RequestBody DeleteDprRequest request) {
        return dprs.delete(id, request.reason());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Send for the engineer's signature, and freeze the rolled-up figures")
    public DprResponse submit(@PathVariable UUID id) {
        return dprs.submit(id);
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "Verify or return. Verifying posts the measured quantities to the measurement book.")
    public DprResponse verify(@PathVariable UUID id,
                              @Valid @RequestBody VerifyDprRequest request) {
        return dprs.decide(id, request);
    }

    @PostMapping("/{id}/approval")
    @Operation(summary = "The office's final approval of a report the engineer has signed",
            description = "Claims nothing. The measured quantities reached the measurement book "
                    + "at verification — this is the countersignature on a document whose "
                    + "figures already count, and it needs dpr:approve rather than dpr:verify.")
    public DprResponse approve(@PathVariable UUID id) {
        return dprs.approve(id);
    }

    @PutMapping("/{id}/plant-rates")
    @Operation(summary = "What the plant on a handed-over report is charged at",
            description = "The supervisor records what stood on the site; whoever the report "
                    + "goes to records what it costs. Held by dpr:verify or dpr:approve, and "
                    + "refused on a draft and on an approved report.")
    public DprResponse priceThePlant(@PathVariable UUID id,
                                     @Valid @RequestBody SetPlantRatesRequest request) {
        return dprs.priceThePlant(id, request);
    }

    @PostMapping("/{id}/photos")
    @Operation(summary = "Link an uploaded site photograph to the day's report")
    public DprResponse attachPhoto(@PathVariable UUID id,
                                   @Valid @RequestBody AttachPhotoRequest request) {
        return dprs.attachPhoto(id, request);
    }

    /**
     * @param sections which parts to print, repeatable. Absent means the whole form. Anything
     *                 left out is named in a line at the foot of the page — see
     *                 {@link DprPdfService} for why an option to omit is only safe alongside a
     *                 statement that something was omitted.
     */
    @GetMapping("/{id}/pdf")
    @Operation(summary = "The printed report, rendered from the frozen figures rather than from today's records")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @RequestParam(required = false)
                                      Set<DprPdfService.Section> sections) {
        DprPdfService.Rendered rendered = pdf.render(id, sections);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rendered.fileName() + "\"")
                .body(rendered.body());
    }
}
