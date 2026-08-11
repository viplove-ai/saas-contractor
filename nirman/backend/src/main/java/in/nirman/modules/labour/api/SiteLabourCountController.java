package in.nirman.modules.labour.api;

import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.DayCountsResponse;
import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.SaveCountsRequest;
import in.nirman.modules.labour.service.SiteLabourCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/labour-counts")
@Tag(name = "Labour counts",
        description = "Head counts per trade for sites whose work is let to labour contractors")
public class SiteLabourCountController {

    private final SiteLabourCountService service;

    public SiteLabourCountController(SiteLabourCountService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "One site's head counts for one day, with whether the site records them at all")
    public DayCountsResponse day(@RequestParam UUID siteId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(siteId, date);
    }

    /** PUT, not POST: the request is the whole day, so re-sending it is not a second day. */
    @PutMapping
    @Operation(summary = "Replace one site's head counts for one day")
    public DayCountsResponse save(@Valid @RequestBody SaveCountsRequest request) {
        return service.save(request);
    }
}
