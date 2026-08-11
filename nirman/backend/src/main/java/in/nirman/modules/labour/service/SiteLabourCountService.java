package in.nirman.modules.labour.service;

import in.nirman.common.BusinessException;
import in.nirman.common.PeriodLockGuard;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.CountLine;
import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.CountResponse;
import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.DayCountsResponse;
import in.nirman.modules.labour.api.dto.SiteLabourCountDtos.SaveCountsRequest;
import in.nirman.modules.labour.domain.SiteLabourCount;
import in.nirman.modules.labour.repository.SiteLabourCountRepository;
import in.nirman.modules.masterdata.domain.LabourContractor;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.repository.LabourContractorRepository;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import in.nirman.security.SiteAccessGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The head counts a site records when the work is let to a labour contractor and there is
 * no muster roll to mark.
 *
 * <p>Three decisions worth knowing before changing anything here.</p>
 *
 * <p><b>The day is replaced, never appended to.</b> The screen sends every trade it is
 * showing, and this rewrites the day to match. That is what makes the save safe to re-send
 * from a phone that lost signal halfway: sending the same day twice leaves one day's
 * counts, not two days added together. Trades left out of the request are deleted, because
 * a trade the supervisor removed from the list is one he is saying was not there — and the
 * alternative, silently keeping it, is how a day ends up with men nobody can account for.
 * </p>
 *
 * <p><b>Zero is kept.</b> "No bar benders came today" is a fact and is stored as a row with
 * a count of zero. Only a trade entirely absent from the request is deleted.</p>
 *
 * <p><b>No money is ever derived from a count.</b> There is no worker, no wage rate and no
 * ledger posting — the contractor bills for the work he did. A head count multiplied by an
 * assumed daily rate would look exactly like a wage figure while being a guess, so this
 * class does not compute one and the DPR does not either. <b>Hours do not change that.</b>
 * They are how long the men stood on the site, which is a fact about the day and belongs on
 * the report; there is still no rate to multiply them by, and nothing here looks for one.</p>
 */
@Service
@Transactional
public class SiteLabourCountService {

    private final SiteLabourCountRepository counts;
    private final SkillCategoryRepository skillCategories;
    private final LabourContractorRepository contractors;
    private final SiteLookup sites;
    private final SiteAccessGuard siteAccessGuard;
    private final PeriodLockGuard periodLockGuard;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public SiteLabourCountService(SiteLabourCountRepository counts,
                                  SkillCategoryRepository skillCategories,
                                  LabourContractorRepository contractors,
                                  SiteLookup sites,
                                  SiteAccessGuard siteAccessGuard,
                                  PeriodLockGuard periodLockGuard,
                                  CurrentUserProvider currentUser,
                                  AuditService audit) {
        this.counts = counts;
        this.skillCategories = skillCategories;
        this.contractors = contractors;
        this.sites = sites;
        this.siteAccessGuard = siteAccessGuard;
        this.periodLockGuard = periodLockGuard;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('worker:read')")
    public DayCountsResponse day(UUID siteId, LocalDate date) {
        siteAccessGuard.assertCanAccess(siteId);
        SiteLookup.SiteInfo site = sites.require(siteId);
        List<CountResponse> lines = toResponses(counts.findBySiteIdAndCountDate(siteId, date));
        return new DayCountsResponse(siteId, date, site.usesOutsourcedLabour(),
                isPeriodLocked(siteId, date),
                lines.stream().mapToInt(CountResponse::headCount).sum(),
                lines.stream().map(CountResponse::manHours).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines);
    }

    /**
     * Replaces one day's counts.
     *
     * <p>Guarded like every other write in this module: the site must be one the caller is
     * posted to, and the month must be open. The period lock matters here even though no
     * money moves — the counts are printed on a report that is filed, and a closed month is
     * closed for the story as well as for the figures.</p>
     */
    @PreAuthorize("hasAuthority('attendance:create')")
    public DayCountsResponse save(SaveCountsRequest request) {
        UUID siteId = request.siteId();
        LocalDate date = request.date();
        siteAccessGuard.assertCanAccess(siteId);
        periodLockGuard.assertOpen(siteId, date, PeriodLockGuard.Module.ATTENDANCE);

        SiteLookup.SiteInfo site = sites.require(siteId);
        if (!site.usesOutsourcedLabour()) {
            throw new BusinessException("labour.counts-not-enabled",
                    site.name() + " records attendance against named workers. Turn on "
                            + "outsourced labour for the site before entering head counts.");
        }
        requireKnownCategories(request.lines());
        requireNoDuplicateTrades(request.lines());

        Map<Key, SiteLabourCount> existing = counts.findBySiteIdAndCountDate(siteId, date).stream()
                .collect(Collectors.toMap(Key::of, row -> row, (a, b) -> a, HashMap::new));

        for (CountLine line : request.lines()) {
            Key key = Key.of(line);
            SiteLabourCount row = existing.remove(key);
            if (row == null) {
                counts.save(new SiteLabourCount(site.orgId(), siteId, date, line.skillCategoryId(),
                        line.labourContractorId(), line.headCount(), line.hours(), line.remarks()));
            } else {
                row.amend(line.headCount(), line.hours(), line.remarks());
            }
        }
        // Whatever the request did not mention is a trade the supervisor took off the day.
        existing.values().forEach(counts::delete);

        audit.record("SITE_LABOUR_COUNT", siteId, "COUNTS_SAVED", null,
                Map.of("date", date.toString(),
                        "lines", request.lines().size(),
                        "headCount", request.lines().stream().mapToInt(CountLine::headCount).sum()),
                null);
        return day(siteId, date);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Skill and contractor together are the row's identity, matching the unique index. A
     * null contractor is its own key rather than a wildcard: "six masons, contractor not
     * named" is a different line from "six masons under Karam Singh", and treating them as
     * one would silently merge two contractors' men.
     */
    private record Key(UUID skillCategoryId, UUID labourContractorId) {

        static Key of(SiteLabourCount row) {
            return new Key(row.getSkillCategoryId(), row.getLabourContractorId());
        }

        static Key of(CountLine line) {
            return new Key(line.skillCategoryId(), line.labourContractorId());
        }
    }

    private void requireKnownCategories(List<CountLine> lines) {
        UUID orgId = currentUser.currentOrgId();
        for (CountLine line : lines) {
            skillCategories.findById(line.skillCategoryId())
                    .filter(category -> category.getOrgId().equals(orgId))
                    .orElseThrow(() -> new BusinessException("labour.skill-unknown",
                            "That trade does not exist."));
            if (line.labourContractorId() != null) {
                contractors.findById(line.labourContractorId())
                        .filter(contractor -> contractor.getOrgId().equals(orgId))
                        .orElseThrow(() -> new BusinessException("labour.contractor-unknown",
                                "That labour contractor does not exist."));
            }
        }
    }

    /**
     * Caught here rather than left to the unique index, because the index would answer with
     * a constraint-violation 500 and this can name the trade that was entered twice.
     */
    private void requireNoDuplicateTrades(List<CountLine> lines) {
        List<Key> keys = lines.stream().map(Key::of).toList();
        if (keys.size() != Set.copyOf(keys).size()) {
            throw new BusinessException("labour.counts-duplicate-trade",
                    "The same trade and contractor appear twice. Enter one line each and "
                            + "add the men together.");
        }
    }

    private List<CountResponse> toResponses(List<SiteLabourCount> rows) {
        Map<UUID, String> categoryNames = skillCategories.findAllById(
                        rows.stream().map(SiteLabourCount::getSkillCategoryId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SkillCategory::getId, SkillCategory::getName));
        Map<UUID, String> contractorNames = contractors.findAllById(
                        rows.stream().map(SiteLabourCount::getLabourContractorId)
                                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(LabourContractor::getId, LabourContractor::getName));

        List<CountResponse> responses = new ArrayList<>(rows.stream()
                .map(row -> new CountResponse(row.getId(), row.getSkillCategoryId(),
                        categoryNames.get(row.getSkillCategoryId()),
                        row.getLabourContractorId(),
                        contractorNames.get(row.getLabourContractorId()),
                        row.getHeadCount(), row.getHours(), row.manHours(), row.getRemarks()))
                .toList());
        responses.sort(Comparator.comparing((CountResponse r) -> r.skillCategoryName() == null
                        ? "" : r.skillCategoryName())
                .thenComparing(r -> r.labourContractorName() == null ? "" : r.labourContractorName()));
        return responses;
    }

    private boolean isPeriodLocked(UUID siteId, LocalDate date) {
        try {
            periodLockGuard.assertOpen(siteId, date, PeriodLockGuard.Module.ATTENDANCE);
            return false;
        } catch (BusinessException e) {
            return true;
        }
    }
}
