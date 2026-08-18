package in.nirman.modules.treasury.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.project.service.ProjectLookup.ContractCalendar;
import in.nirman.modules.tender.service.NitLookup;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.CreateSecurityRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ForfeitRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.LodgeRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ProposalResponse;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.RedeployRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.ReleaseRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.RetainedRequest;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.SecurityResponse;
import in.nirman.modules.treasury.api.dto.TreasuryDtos.UpdateSecurityRequest;
import in.nirman.modules.treasury.domain.ProjectSecurity;
import in.nirman.modules.treasury.repository.ProjectSecurityRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The register of deposits and guarantees: what is lodged against each contract, and when it
 * comes back.
 *
 * <p>Reading is the accountant's — he is the man who chases a matured FDR. Writing is the
 * administrator's, because recording that a deposit has been lodged, released or forfeited is a
 * statement about the company's money to a department.</p>
 *
 * <p>Both permissions are held only by company-wide roles, so a caller here already sees every
 * site; the organisation is the whole of the scope and there is no {@code SiteAccessGuard} call
 * to make. A deposit belongs to a contract, not to a place.</p>
 */
@Service
@Transactional
public class ProjectSecurityService {

    private final ProjectSecurityRepository securities;
    private final ProjectLookup projects;
    private final NitLookup tenders;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public ProjectSecurityService(ProjectSecurityRepository securities, ProjectLookup projects,
                                  NitLookup tenders, CurrentUserProvider currentUser,
                                  AuditService audit) {
        this.securities = securities;
        this.projects = projects;
        this.tenders = tenders;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('security:read')")
    public List<SecurityResponse> forProject(UUID projectId) {
        ContractCalendar contract = requireContract(projectId);
        Map<UUID, ContractCalendar> named = namesFor(List.of(contract));
        return securities
                .findByOrgIdAndProjectIdOrderBySecurityTypeAscCreatedAtAsc(orgId(), projectId)
                .stream()
                .map(security -> toResponse(security, named, LocalDate.now()))
                .toList();
    }

    /**
     * What the contract says each deposit ought to be.
     *
     * <p>Computed on every call and stored nowhere, in the manner of every other rolled-up
     * figure here. The office reads it once, on the form, and then owns the number — an FDR is
     * bought for whatever figure the bank issued, and a register that kept recomputing its rows
     * would report the rule rather than the deposit.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('security:read')")
    public List<ProposalResponse> proposeFor(UUID projectId) {
        ContractCalendar contract = requireContract(projectId);
        SecurityProposer.NoticeTerms notice = tenders.forProject(projectId)
                .map(terms -> new SecurityProposer.NoticeTerms(
                        terms.emdAmount(), terms.performanceGuaranteePercent(),
                        terms.securityDepositPercent(),
                        terms.additionalGuarantee() == null ? null
                                : terms.additionalGuarantee().thresholdPercent(),
                        terms.additionalGuarantee() == null ? null
                                : terms.additionalGuarantee().method(),
                        terms.additionalGuarantee() == null ? null
                                : terms.additionalGuarantee().percent()))
                .orElse(SecurityProposer.NoticeTerms.NONE);

        List<ProjectSecurity> existing = securities
                .findByOrgIdAndProjectIdOrderBySecurityTypeAscCreatedAtAsc(orgId(), projectId);

        return SecurityProposer.propose(factsOf(contract, notice)).stream()
                .map(proposal -> new ProposalResponse(
                        proposal.type(), proposal.instrument(), proposal.amount(),
                        proposal.basis(), proposal.expectedReleaseOn(),
                        existing.stream()
                                .anyMatch(row -> row.getSecurityType() == proposal.type())))
                .toList();
    }

    /**
     * The estimated cost, from the best source that has one.
     *
     * <p>Three, in order of authority: what the administrator typed on the project, what the
     * notice said, and — failing both — the contract value backed out of the bid, since a
     * percentage-rate tender prices the contract at the estimate adjusted by the quote. The
     * third is arithmetic on two figures the project already holds and is exact whenever the
     * bid was recorded; it is last because it is a derivation and the first two are readings.</p>
     */
    static BigDecimal estimatedCost(ContractCalendar contract, BigDecimal noticeEstimate) {
        if (contract.estimatedCost() != null) {
            return contract.estimatedCost();
        }
        if (noticeEstimate != null) {
            return noticeEstimate;
        }
        if (contract.contractValue() == null || contract.quotedPercent() == null) {
            return null;
        }
        BigDecimal factor = BigDecimal.ONE
                .add(contract.quotedPercent().movePointLeft(2));
        if (factor.signum() <= 0) {
            return null;
        }
        return contract.contractValue()
                .divide(factor, 2, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ writes

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse create(CreateSecurityRequest request) {
        ContractCalendar contract = requireContract(request.projectId());
        if (request.securityType() == ProjectSecurity.Type.SECURITY_DEPOSIT
                && securities.existsByOrgIdAndProjectIdAndSecurityType(
                        orgId(), request.projectId(), ProjectSecurity.Type.SECURITY_DEPOSIT)) {
            throw BusinessException.conflict("security.retention-exists",
                    "This contract already carries a security deposit. It is one running total "
                            + "that grows bill by bill, not a second deposit — amend the "
                            + "existing one.");
        }
        if (request.instrument() == ProjectSecurity.Instrument.BILL_RETENTION
                && request.securityType() != ProjectSecurity.Type.SECURITY_DEPOSIT) {
            throw new BusinessException("security.retention-wrong-type",
                    "Only a security deposit is withheld from bills. Earnest money and "
                            + "guarantees are lodged before there is a bill to deduct from.");
        }

        ProjectSecurity security = new ProjectSecurity(orgId(), request.projectId(),
                request.securityType(), request.instrument(), request.amount());
        security.setBasis(request.basis());
        security.setExpectedReleaseOn(request.expectedReleaseOn());
        security.setNotes(request.notes());
        securities.save(security);

        audit.record("PROJECT_SECURITY", security.getId(), "CREATE", null,
                Map.of("project", contract.code(), "type", request.securityType().name(),
                        "amount", request.amount()), null);
        return toResponse(security, namesFor(List.of(contract)), LocalDate.now());
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse update(UUID id, UpdateSecurityRequest request) {
        ProjectSecurity security = require(id, request.version());
        if (security.getStatus() == ProjectSecurity.Status.RELEASED
                || security.getStatus() == ProjectSecurity.Status.FORFEITED) {
            throw new BusinessException("security.settled",
                    "This deposit is settled. Its figures are what the department acted on and "
                            + "are kept as they stood.");
        }
        Map<String, Object> before = Map.of("amount", security.getAmount(),
                "expectedReleaseOn", String.valueOf(security.getExpectedReleaseOn()));

        security.setInstrument(request.instrument());
        security.setAmount(request.amount());
        security.setBasis(request.basis());
        security.setReferenceNo(request.referenceNo());
        security.setBankName(request.bankName());
        security.setBranch(request.branch());
        security.setLodgedOn(request.lodgedOn());
        security.setMaturityOn(request.maturityOn());
        security.setExpectedReleaseOn(request.expectedReleaseOn());
        security.setNotes(request.notes());
        assertAmountCoversWhatIsHeld(security);

        audit.record("PROJECT_SECURITY", security.getId(), "UPDATE", before,
                Map.of("amount", security.getAmount(),
                        "expectedReleaseOn", String.valueOf(security.getExpectedReleaseOn())),
                null);
        return respond(security);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse lodge(UUID id, LodgeRequest request) {
        ProjectSecurity security = require(id, request.version());
        security.lodge(request.lodgedOn(), request.referenceNo(), request.bankName(),
                request.branch(), request.maturityOn());
        if (request.expectedReleaseOn() != null) {
            security.setExpectedReleaseOn(request.expectedReleaseOn());
        }
        audit.record("PROJECT_SECURITY", security.getId(), "LODGE", null,
                Map.of("on", request.lodgedOn().toString(),
                        "amount", security.getHeldAmount(),
                        "reference", String.valueOf(request.referenceNo())), null);
        return respond(security);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse recordRetained(UUID id, RetainedRequest request) {
        ProjectSecurity security = require(id, request.version());
        security.recordRetained(request.retainedToDate(), request.asOf());
        audit.record("PROJECT_SECURITY", security.getId(), "RETAIN", null,
                Map.of("retainedToDate", request.retainedToDate(),
                        "asOf", request.asOf().toString()), null);
        return respond(security);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse release(UUID id, ReleaseRequest request) {
        ProjectSecurity security = require(id, request.version());
        BigDecimal released = security.getHeldAmount();
        security.release(request.releasedOn(), request.reference());
        audit.record("PROJECT_SECURITY", security.getId(), "RELEASE", null,
                Map.of("on", request.releasedOn().toString(), "amount", released,
                        "reference", String.valueOf(request.reference())), null);
        return respond(security);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse redeploy(UUID id, RedeployRequest request) {
        ProjectSecurity security = require(id, request.version());
        ContractCalendar destination = requireContract(request.toProjectId());
        security.redeployTo(request.toProjectId());
        audit.record("PROJECT_SECURITY", security.getId(), "REDEPLOY", null,
                Map.of("toProject", destination.code(), "amount", security.getAmount()), null);
        return respond(security);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public SecurityResponse forfeit(UUID id, ForfeitRequest request) {
        ProjectSecurity security = require(id, request.version());
        BigDecimal lost = security.getHeldAmount();
        security.forfeit(request.reason());
        audit.record("PROJECT_SECURITY", security.getId(), "FORFEIT", null,
                Map.of("amount", lost, "reason", request.reason()), request.reason());
        return respond(security);
    }

    /**
     * Removes a deposit that was recorded in error.
     *
     * <p>Only while nothing has happened to it. Once money has gone out the row is the company's
     * account of where it went, and a register that can forget an FDR is a register the office
     * cannot reconcile against a bank statement — release it or forfeit it instead, both of
     * which leave the history standing.</p>
     */
    @PreAuthorize("hasAuthority('security:write')")
    public void delete(UUID id) {
        ProjectSecurity security = securities.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Security", id));
        if (security.getStatus() != ProjectSecurity.Status.DUE) {
            throw new BusinessException("security.not-removable",
                    "This deposit has been " + security.getStatus().name().toLowerCase()
                            + ". Release or forfeit it instead — the register has to keep saying "
                            + "where the money went.");
        }
        audit.record("PROJECT_SECURITY", security.getId(), "DELETE",
                Map.of("type", security.getSecurityType().name(),
                        "amount", security.getAmount()), null, null);
        securities.delete(security);
    }

    // ------------------------------------------------------------------ internals

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private ProjectSecurity require(UUID id, Long version) {
        ProjectSecurity security = securities.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Security", id));
        if (!security.getVersion().equals(version)) {
            throw new OptimisticLockingFailureException(
                    "Security " + id + " was changed by someone else");
        }
        return security;
    }

    private ContractCalendar requireContract(UUID projectId) {
        return projects.calendar(projectId)
                .orElseThrow(() -> BusinessException.notFound("Project", projectId));
    }

    private void assertAmountCoversWhatIsHeld(ProjectSecurity security) {
        if (security.getHeldAmount().compareTo(security.getAmount()) > 0) {
            throw new BusinessException("security.amount-below-held",
                    "The department is already holding more than that. Lower the held figure "
                            + "first if the deduction was recorded wrongly.");
        }
    }

    /**
     * Both ends of a redeployment, resolved in one query: a released deposit names the tender
     * it went on to fund, and asking only for its own project would leave that name blank on
     * the very response that set it.
     */
    private SecurityResponse respond(ProjectSecurity security) {
        List<UUID> involved = security.getRedeployedToProjectId() == null
                ? List.of(security.getProjectId())
                : List.of(security.getProjectId(), security.getRedeployedToProjectId());
        return toResponse(security, namesFor(projects.calendars(involved)), LocalDate.now());
    }

    private Map<UUID, ContractCalendar> namesFor(List<ContractCalendar> contracts) {
        Map<UUID, ContractCalendar> byId = new LinkedHashMap<>();
        contracts.forEach(contract -> byId.put(contract.id(), contract));
        return byId;
    }

    static SecurityProposer.ContractFacts factsOf(ContractCalendar contract,
                                                  SecurityProposer.NoticeTerms notice) {
        return new SecurityProposer.ContractFacts(
                estimatedCost(contract, null),
                contract.contractValue(),
                contract.workNature() == null ? null
                        : SecurityProposer.WorkNature.valueOf(contract.workNature()),
                contract.bidOpeningDate(),
                contract.allotmentLetterDate(),
                contract.completionOn(),
                contract.defectLiabilityMonths(),
                notice);
    }

    /**
     * A row, with today's countdowns worked out against {@code asOf}. Static and package-private
     * so the dashboard builds its rows the same way rather than growing a second copy of the
     * arithmetic that would disagree with this one.
     */
    static SecurityResponse toResponse(ProjectSecurity security,
                                       Map<UUID, ContractCalendar> contracts, LocalDate asOf) {
        ContractCalendar contract = contracts.get(security.getProjectId());
        ContractCalendar destination = security.getRedeployedToProjectId() == null ? null
                : contracts.get(security.getRedeployedToProjectId());
        Integer daysToRelease = security.getExpectedReleaseOn() == null
                || security.getStatus() != ProjectSecurity.Status.LODGED
                ? null
                : (int) ChronoUnit.DAYS.between(asOf, security.getExpectedReleaseOn());

        return new SecurityResponse(
                security.getId(),
                security.getProjectId(),
                contract == null ? null : contract.code(),
                contract == null ? null : contract.name(),
                security.getSecurityType(),
                security.getInstrument(),
                security.getStatus(),
                security.getAmount(),
                security.getHeldAmount(),
                security.getBasis(),
                security.getReferenceNo(),
                security.getBankName(),
                security.getBranch(),
                security.getLodgedOn(),
                security.getMaturityOn(),
                security.getExpectedReleaseOn(),
                security.getReleasedOn(),
                security.getReleaseReference(),
                security.getRedeployedToProjectId(),
                destination == null ? null : destination.code(),
                security.getForfeitedReason(),
                security.getNotes(),
                daysToRelease,
                security.isReleasableBy(asOf) || security.needsRenewalBy(asOf),
                security.isFreeToReuse(),
                security.getVersion());
    }

    /** For the dashboard, which resolves every contract it needs in one query. */
    static Optional<ContractCalendar> contractOf(Map<UUID, ContractCalendar> contracts,
                                                 ProjectSecurity security) {
        return Optional.ofNullable(contracts.get(security.getProjectId()));
    }
}
