package in.nirman.modules.tender.service;

import in.nirman.modules.tender.domain.NitDocument;
import in.nirman.modules.tender.domain.NitMilestone;
import in.nirman.modules.tender.parser.AllowedTime;
import in.nirman.modules.tender.repository.NitDocumentRepository;
import in.nirman.modules.tender.repository.NitInterimMinimumRepository;
import in.nirman.modules.tender.repository.NitMilestoneRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link NitLookup}. Carries no permission check of its own: it answers a caller that has already
 * passed the one that got it here, in the manner of every other {@code *Lookup} in this codebase.
 */
@Service
@Transactional(readOnly = true)
public class NitLookupService implements NitLookup {

    private final NitDocumentRepository documents;
    private final NitMilestoneRepository milestones;
    private final NitInterimMinimumRepository minimums;
    private final CurrentUserProvider currentUser;

    public NitLookupService(NitDocumentRepository documents, NitMilestoneRepository milestones,
                            NitInterimMinimumRepository minimums,
                            CurrentUserProvider currentUser) {
        this.documents = documents;
        this.milestones = milestones;
        this.minimums = minimums;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<TenderTerms> forProject(UUID projectId) {
        return documents.findByProjectIdAndOrgIdAndDeletedAtIsNull(
                projectId, currentUser.currentOrgId()).map(this::toTerms);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read straight off the stored notice, with no fallback to a current schedule: what a
     * tender is priced under is a fact about that tender, and offering today's edition where
     * the notice was silent would be the system inventing the answer.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RateBasis> rateBasis(UUID projectId) {
        return documents.findByProjectIdAndOrgIdAndDeletedAtIsNull(
                        projectId, currentUser.currentOrgId())
                .map(document -> new RateBasis(document.getCivilDsrYear(),
                        document.getCivilCostIndexPercent(), document.getElectricalDsrYear(),
                        document.getElectricalCostIndexPercent()));
    }

    private TenderTerms toTerms(NitDocument document) {
        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        minimums.findByNitDocumentId(document.getId()).forEach(minimum ->
                thresholds.put(minimum.getWorkPart() == null ? "" : minimum.getWorkPart(),
                        minimum.getAmount()));

        List<MilestoneTerm> terms = new ArrayList<>();
        for (NitMilestone milestone
                : milestones.findByNitDocumentIdOrderBySequenceNoAsc(document.getId())) {
            terms.add(new MilestoneTerm(milestone.getSequenceNo(), milestone.getDescription(),
                    days(milestone.getTimeAllowed()), milestone.getFinancialPercent(),
                    milestone.getWithheldPercent(), milestone.isPhysical()));
        }
        return new TenderTerms(document.getId(), document.getEstimatedCost(),
                document.getEmdAmount(), document.getPerformanceGuaranteePercent(),
                document.getSecurityDepositPercent(), days(document.getCompletionTime()),
                document.getStartReckoningDays(), document.getClause7aApplicable(),
                thresholds, terms,
                document.getApgThresholdPercent() == null ? null
                        : new AdditionalGuaranteeTerm(document.getApgThresholdPercent(),
                                document.getApgMethod(), document.getApgPercent()));
    }

    /**
     * Months become days at thirty apiece, and only here. The engine works in day offsets from
     * commencement, so the calendar month a notice printed has to be flattened somewhere — this
     * is the one place it happens, and it is why {@link AllowedTime} keeps its unit right up to
     * the boundary rather than being folded at extraction.
     */
    private static Integer days(AllowedTime time) {
        return time == null ? null : time.approximateDays();
    }
}
