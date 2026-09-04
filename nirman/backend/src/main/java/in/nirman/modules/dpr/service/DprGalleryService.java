package in.nirman.modules.dpr.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PageResponse;
import in.nirman.modules.attachment.service.AttachmentLookup;
import in.nirman.modules.dpr.api.dto.DprDtos.GalleryPhotoResponse;
import in.nirman.modules.dpr.repository.DprPhotoRepository;
import in.nirman.modules.dpr.repository.PhotoOnReport;
import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A project's photographs, read off its daily reports.
 *
 * <p>The report is where a site is photographed — it is refused at handover without one — so
 * every picture of the work is already on file, one report at a time, findable only by
 * knowing the day. This reads them the other way round: the whole project, newest day first,
 * each picture naming the report and the site it belongs to. Nothing is stored for it and no
 * picture is copied anywhere: a gallery that kept its own rows would be a second list of the
 * same files, and it would stop matching the first the day a report was deleted.</p>
 *
 * <p>Behind the same permissions as reading a report, because that is what it is — whoever
 * may open the day may see what it was a report of — and narrowed to the caller's sites the
 * way the register is. A draft's photograph is shown: the picture is of the site whether or
 * not the day has been handed over yet, and the status is beside it.</p>
 */
@Service
@Transactional(readOnly = true)
public class DprGalleryService {

    private final DprPhotoRepository photos;
    private final AttachmentLookup attachments;
    private final ProjectLookup projects;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final CurrentUserProvider currentUser;

    public DprGalleryService(DprPhotoRepository photos, AttachmentLookup attachments,
                             ProjectLookup projects, SiteLookup sites,
                             SiteAccessGuard siteAccessGuard, CurrentUserProvider currentUser) {
        this.photos = photos;
        this.attachments = attachments;
        this.projects = projects;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAnyAuthority('dpr:draft', 'dpr:verify', 'dpr:approve')")
    public PageResponse<GalleryPhotoResponse> gallery(UUID projectId, UUID siteId, LocalDate from,
                                                      LocalDate to, Pageable pageable) {
        projects.contract(projectId)
                .orElseThrow(() -> BusinessException.notFound("Project", projectId));
        if (siteId != null) {
            siteAccessGuard.assertCanAccess(siteId);
        }
        boolean restricted = !currentUser.seesAllSites();
        Collection<UUID> visible = restricted ? currentUser.assignedSiteIds() : List.of();
        if (restricted && visible.isEmpty()) {
            return new PageResponse<>(List.of(), 0, pageable.getPageSize(), 0, 0, true, true);
        }

        // Named once per page, not once per picture: a project has a handful of sites and a
        // page has up to a hundred photographs.
        Map<UUID, SiteLookup.SiteInfo> siteNames = sites.forProject(projectId).stream()
                .collect(Collectors.toMap(SiteLookup.SiteInfo::id, Function.identity()));
        Map<UUID, String> fileNames = new HashMap<>();

        // Each ignored filter still carries a harmless typed value, so the driver can prepare
        // the statement — see the repository note.
        return PageResponse.from(
                photos.gallery(currentUser.currentOrgId(), projectId,
                        siteId == null, siteId == null ? projectId : siteId,
                        from == null, from == null ? LocalDate.EPOCH : from,
                        to == null, to == null ? LocalDate.EPOCH : to,
                        restricted, visible, pageable),
                row -> toResponse(row, siteNames, fileNames));
    }

    private GalleryPhotoResponse toResponse(PhotoOnReport row,
                                            Map<UUID, SiteLookup.SiteInfo> siteNames,
                                            Map<UUID, String> fileNames) {
        SiteLookup.SiteInfo site = siteNames.get(row.report().getSiteId());
        String fileName = fileNames.computeIfAbsent(row.photo().getAttachmentId(), this::fileNameOf);
        return new GalleryPhotoResponse(row.photo().getId(), row.photo().getAttachmentId(),
                row.photo().getCaption(), row.photo().getTakenAt(), fileName,
                row.report().getId(), row.report().getDprNumber(), row.report().getReportDate(),
                row.report().getWorkflowStatus(), row.report().getSiteId(),
                site == null ? null : site.code(), site == null ? null : site.name());
    }

    /**
     * The file's own name, or nothing. A photograph whose file has gone is still a row on a
     * report and still belongs in the count; the screen says the picture is not loading, which
     * is the truth, rather than the whole page failing on one missing object.
     */
    private String fileNameOf(UUID attachmentId) {
        try {
            return attachments.require(attachmentId).fileName();
        } catch (BusinessException e) {
            return null;
        }
    }
}
