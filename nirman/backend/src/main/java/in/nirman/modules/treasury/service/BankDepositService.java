package in.nirman.modules.treasury.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.project.service.ProjectLookup;
import in.nirman.modules.project.service.ProjectLookup.ContractCalendar;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.AddPhotoRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.CloseDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.CreateDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.DepositResponse;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.PhotoRow;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.PledgeRow;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.RegisterResponse;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.ReopenDepositRequest;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.Summary;
import in.nirman.modules.treasury.api.dto.BankDepositDtos.UpdateDepositRequest;
import in.nirman.modules.treasury.domain.BankDeposit;
import in.nirman.modules.treasury.domain.BankDepositPhoto;
import in.nirman.modules.treasury.domain.ProjectSecurity;
import in.nirman.modules.treasury.repository.BankDepositPhotoRepository;
import in.nirman.modules.treasury.repository.BankDepositRepository;
import in.nirman.modules.treasury.repository.ProjectSecurityRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The register of fixed deposits the company holds, across every contract and none.
 *
 * <p>{@link ProjectSecurityService} answers "what has this contract lodged". This answers the
 * two questions that one cannot: what do we hold altogether, and which of it is free to pledge
 * to the next tender. Neither had an answer before V42, because a deposit only existed as
 * columns on the contract it happened to be committed to.</p>
 *
 * <p>Its permissions are that service's, deliberately: {@code security:read} and
 * {@code security:write} already name the office moving the company's money, and buying a
 * certificate is the same act by the same person as lodging it. An organisation able to grant
 * one and withhold the other would have a register somebody can pledge from and not enter
 * into.</p>
 *
 * <p><b>What is pledged is derived, never stored.</b> Every read joins the securities pointing
 * at each certificate; a status column saying PLEDGED would be the copy that goes stale, and it
 * would go stale in the direction that matters — showing money as committed months after a
 * department returned it.</p>
 */
@Service
@Transactional
public class BankDepositService {

    /** How far ahead a maturing certificate is worth counting on the register's summary. */
    private static final int MATURING_SOON_DAYS = 90;

    private final BankDepositRepository deposits;
    private final BankDepositPhotoRepository photos;
    private final ProjectSecurityRepository securities;
    private final ProjectLookup projects;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public BankDepositService(BankDepositRepository deposits, BankDepositPhotoRepository photos,
                              ProjectSecurityRepository securities, ProjectLookup projects,
                              CurrentUserProvider currentUser, AuditService audit) {
        this.deposits = deposits;
        this.photos = photos;
        this.securities = securities;
        this.projects = projects;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Every certificate, with its pledges and its pictures, and the totals underneath.
     *
     * <p>Three queries whatever the register's size — the deposits, their pledges, their photos
     * — rather than two per row. The contract names come from one more, through the project
     * module's own lookup, because a register that printed project ids would name nobody.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('security:read')")
    public RegisterResponse register() {
        List<BankDeposit> rows = deposits.findByOrgIdOrderByIssuedOnDesc(orgId());
        if (rows.isEmpty()) {
            return new RegisterResponse(List.of(), emptySummary());
        }
        List<UUID> ids = rows.stream().map(BankDeposit::getId).toList();

        Map<UUID, List<ProjectSecurity>> pledges = new LinkedHashMap<>();
        securities.findByBankDepositIdInOrderByCreatedAtAsc(ids).forEach(security ->
                pledges.computeIfAbsent(security.getBankDepositId(), key -> new ArrayList<>())
                        .add(security));

        Map<UUID, List<BankDepositPhoto>> pictures = new LinkedHashMap<>();
        photos.findByDepositIdInOrderBySortOrderAscIdAsc(ids).forEach(photo ->
                pictures.computeIfAbsent(photo.getDepositId(), key -> new ArrayList<>())
                        .add(photo));

        Map<UUID, ContractCalendar> contracts = contractsFor(pledges.values());
        LocalDate today = LocalDate.now();

        List<DepositResponse> responses = rows.stream()
                .map(row -> toResponse(row,
                        pledges.getOrDefault(row.getId(), List.of()),
                        pictures.getOrDefault(row.getId(), List.of()),
                        contracts, today))
                .toList();
        return new RegisterResponse(responses, summarise(responses));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('security:read')")
    public DepositResponse get(UUID id) {
        return present(require(id));
    }

    // ------------------------------------------------------------------ writes

    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse create(CreateDepositRequest request) {
        deposits.findByOrgIdAndDepositNumberIgnoreCase(orgId(), request.depositNumber().trim())
                .ifPresent(existing -> {
                    throw BusinessException.conflict("deposit.duplicate-number",
                            "%s is already in the register, issued on %s for %s. Two rows for "
                                    .formatted(existing.getDepositNumber(), existing.getIssuedOn(),
                                            existing.getAmount().toPlainString())
                                    + "one certificate would split what the company holds into "
                                    + "two figures that never add up.");
                });
        if (request.maturityOn() != null && request.maturityOn().isBefore(request.issuedOn())) {
            throw new BusinessException("deposit.maturity",
                    "A deposit cannot mature before it was issued.");
        }

        BankDeposit deposit = new BankDeposit(orgId(), request.depositNumber().trim(),
                request.bankName().trim(), request.amount(), request.issuedOn());
        deposit.amend(request.depositNumber().trim(), request.bankName().trim(), request.branch(),
                request.amount(), request.issuedOn(), request.maturityOn(),
                request.interestRate(), request.notes());
        deposits.save(deposit);

        audit.record("BANK_DEPOSIT", deposit.getId(), "CREATE", null,
                Map.of("depositNumber", deposit.getDepositNumber(),
                        "bankName", deposit.getBankName(),
                        "amount", deposit.getAmount(),
                        "issuedOn", deposit.getIssuedOn().toString()), null);
        return present(deposit);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse update(UUID id, UpdateDepositRequest request) {
        BankDeposit deposit = require(id);
        assertVersion(deposit, request.version());

        String number = request.depositNumber().trim();
        deposits.findByOrgIdAndDepositNumberIgnoreCase(orgId(), number).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw BusinessException.conflict("deposit.duplicate-number",
                        "%s is already in the register.".formatted(other.getDepositNumber()));
            }
        });

        Map<String, Object> before = Map.of("depositNumber", deposit.getDepositNumber(),
                "amount", deposit.getAmount());
        deposit.amend(number, request.bankName().trim(), request.branch(), request.amount(),
                request.issuedOn(), request.maturityOn(), request.interestRate(),
                request.notes());
        audit.record("BANK_DEPOSIT", id, "UPDATE", before,
                Map.of("depositNumber", deposit.getDepositNumber(),
                        "amount", deposit.getAmount()), null);
        return present(deposit);
    }

    /**
     * The bank has paid it out.
     *
     * <p>Refused while a department is still holding it. The register would otherwise say the
     * money is gone while a contract's own screen says it is lodged, and one of the two is the
     * screen somebody reads before telling the department it has been released.</p>
     */
    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse close(UUID id, CloseDepositRequest request) {
        BankDeposit deposit = require(id);
        assertVersion(deposit, request.version());
        if (securities.existsByBankDepositIdAndStatus(id, ProjectSecurity.Status.LODGED)) {
            throw new BusinessException("deposit.still-pledged",
                    "%s is still lodged against a contract. Release it there first — a "
                            .formatted(deposit.getDepositNumber())
                            + "certificate the register calls closed and a department is "
                            + "holding is the same money counted two ways.");
        }
        deposit.close(request.closedOn(), request.reason());
        audit.record("BANK_DEPOSIT", id, "CLOSE", null,
                Map.of("depositNumber", deposit.getDepositNumber(),
                        "closedOn", request.closedOn().toString(),
                        "amount", deposit.getAmount()), request.reason());
        return present(deposit);
    }

    /** Undoes a closing entered against the wrong certificate. */
    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse reopen(UUID id, ReopenDepositRequest request) {
        BankDeposit deposit = require(id);
        assertVersion(deposit, request.version());
        deposit.reopen();
        audit.record("BANK_DEPOSIT", id, "REOPEN", null,
                Map.of("depositNumber", deposit.getDepositNumber()), null);
        return present(deposit);
    }

    /**
     * A photograph of the certificate.
     *
     * <p>The attachment is uploaded first and linked here, the order every other photograph in
     * the system takes: an attachment with no owner is a stray file somebody can clean up, while
     * a register row pointing at a file that was never stored is a broken picture nobody can
     * fix. A repeated link is the retried upload and answers with the row it already holds.</p>
     */
    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse addPhoto(UUID id, AddPhotoRequest request) {
        BankDeposit deposit = require(id);
        if (photos.existsByDepositIdAndAttachmentId(id, request.attachmentId())) {
            return present(deposit);
        }
        int next = photos.findByDepositIdOrderBySortOrderAscIdAsc(id).size();
        photos.save(new BankDepositPhoto(id, request.attachmentId(), request.caption(), next));
        audit.record("BANK_DEPOSIT", id, "ATTACH", null,
                Map.of("attachmentId", request.attachmentId().toString()), null);
        return present(deposit);
    }

    @PreAuthorize("hasAuthority('security:write')")
    public DepositResponse removePhoto(UUID id, UUID attachmentId) {
        BankDeposit deposit = require(id);
        photos.deleteByDepositIdAndAttachmentId(id, attachmentId);
        audit.record("BANK_DEPOSIT", id, "DETACH", null,
                Map.of("attachmentId", attachmentId.toString()), null);
        return present(deposit);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Checked here rather than left to the deposit's own {@code @Version}, so a stale form is
     * refused before any of it is applied. Same 409 either way; this one arrives before the
     * duplicate-number check has had a chance to complain about the wrong thing.
     */
    private void assertVersion(BankDeposit deposit, Long version) {
        if (!deposit.getVersion().equals(version)) {
            throw new OptimisticLockingFailureException(
                    "Deposit " + deposit.getId() + " was changed by someone else");
        }
    }

    private DepositResponse present(BankDeposit deposit) {
        List<ProjectSecurity> pledges =
                securities.findByBankDepositIdInOrderByCreatedAtAsc(List.of(deposit.getId()));
        return toResponse(deposit, pledges,
                photos.findByDepositIdOrderBySortOrderAscIdAsc(deposit.getId()),
                contractsFor(List.of(pledges)), LocalDate.now());
    }

    private DepositResponse toResponse(BankDeposit deposit, List<ProjectSecurity> pledges,
                                       List<BankDepositPhoto> pictures,
                                       Map<UUID, ContractCalendar> contracts, LocalDate today) {
        List<PledgeRow> history = pledges.stream()
                .map(security -> toPledgeRow(security, contracts))
                .toList();
        PledgeRow live = history.stream()
                .filter(row -> row.status() == ProjectSecurity.Status.LODGED)
                .findFirst().orElse(null);
        List<PhotoRow> photoRows = pictures.stream()
                .map(photo -> new PhotoRow(photo.getAttachmentId(), photo.getCaption()))
                .toList();
        Integer daysToMaturity = deposit.getMaturityOn() == null ? null
                : (int) ChronoUnit.DAYS.between(today, deposit.getMaturityOn());

        return new DepositResponse(deposit.getId(), deposit.getDepositNumber(),
                deposit.getBankName(), deposit.getBranch(), deposit.getAmount(),
                deposit.getIssuedOn(), deposit.getMaturityOn(), deposit.getInterestRate(),
                deposit.getStatus(), deposit.getClosedOn(), deposit.getClosedReason(),
                deposit.getNotes(), live, history, photoRows, daysToMaturity,
                deposit.getVersion());
    }

    private PledgeRow toPledgeRow(ProjectSecurity security, Map<UUID, ContractCalendar> contracts) {
        ContractCalendar contract = contracts.get(security.getProjectId());
        return new PledgeRow(security.getId(), security.getProjectId(),
                contract == null ? null : contract.code(),
                contract == null ? null : contract.name(),
                security.getSecurityType(), security.getStatus(),
                security.getLodgedOn(), security.getReleasedOn());
    }

    /** One lookup for every contract named anywhere in the register. */
    private Map<UUID, ContractCalendar> contractsFor(Collection<List<ProjectSecurity>> pledges) {
        List<UUID> projectIds = pledges.stream()
                .flatMap(List::stream)
                .map(ProjectSecurity::getProjectId)
                .distinct()
                .toList();
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ContractCalendar> byId = new LinkedHashMap<>();
        projects.calendars(projectIds).forEach(contract -> byId.put(contract.id(), contract));
        return byId;
    }

    /**
     * The four figures the office asks for, and the one it could not ask before: what is held
     * and pledged to nothing is the money available for the next tender.
     */
    private Summary summarise(List<DepositResponse> rows) {
        int heldCount = 0;
        int pledgedCount = 0;
        int idleCount = 0;
        int closedCount = 0;
        int maturingSoon = 0;
        BigDecimal held = BigDecimal.ZERO;
        BigDecimal pledged = BigDecimal.ZERO;
        BigDecimal idle = BigDecimal.ZERO;

        for (DepositResponse row : rows) {
            if (row.status() == BankDeposit.Status.CLOSED) {
                closedCount++;
                continue;
            }
            heldCount++;
            held = held.add(row.amount());
            if (row.pledgedTo() != null) {
                pledgedCount++;
                pledged = pledged.add(row.amount());
            } else {
                idleCount++;
                idle = idle.add(row.amount());
            }
            if (row.daysToMaturity() != null && row.daysToMaturity() <= MATURING_SOON_DAYS) {
                maturingSoon++;
            }
        }
        return new Summary(heldCount, held, pledgedCount, pledged, idleCount, idle, closedCount,
                maturingSoon);
    }

    private static Summary emptySummary() {
        return new Summary(0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0);
    }

    private BankDeposit require(UUID id) {
        return deposits.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Fixed deposit", id));
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }
}
