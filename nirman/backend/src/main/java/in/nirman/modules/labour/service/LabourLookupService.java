package in.nirman.modules.labour.service;

import in.nirman.modules.labour.domain.AttendanceRecord;
import in.nirman.modules.labour.domain.AttendanceStatus;
import in.nirman.modules.labour.domain.WorkflowStatus;
import in.nirman.modules.labour.domain.SiteLabourCount;
import in.nirman.modules.labour.domain.Worker;
import in.nirman.modules.labour.repository.AttendanceRecordRepository;
import in.nirman.modules.labour.repository.SiteLabourCountRepository;
import in.nirman.modules.labour.repository.WorkerRepository;
import in.nirman.modules.masterdata.domain.LabourContractor;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.repository.LabourContractorRepository;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.modules.project.service.SiteLookup;
import in.nirman.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link LabourLookup}, computed from the attendance rows themselves rather than from a
 * stored roll-up.
 *
 * <p>That is the whole point of the class. The DPR's exit criterion is that <b>the prefill
 * matches the underlying records exactly</b>, and the only way to keep that true through a
 * late correction, a rejection or a worker added to the muster at five o'clock is to derive
 * the figures on every call. A cached daily total would be a second version of the truth,
 * and the version people would notice is whichever one was wrong.</p>
 */
@Service
@Transactional(readOnly = true)
public class LabourLookupService implements LabourLookup {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final AttendanceRecordRepository records;
    private final WorkerRepository workers;
    private final SiteLabourCountRepository labourCounts;
    private final SkillCategoryRepository skillCategories;
    private final LabourContractorRepository contractors;
    private final SiteLookup sites;
    private final CurrentUserProvider currentUser;

    public LabourLookupService(AttendanceRecordRepository records, WorkerRepository workers,
                              SiteLabourCountRepository labourCounts,
                              SkillCategoryRepository skillCategories,
                              LabourContractorRepository contractors,
                              SiteLookup sites,
                              CurrentUserProvider currentUser) {
        this.records = records;
        this.workers = workers;
        this.labourCounts = labourCounts;
        this.skillCategories = skillCategories;
        this.contractors = contractors;
        this.sites = sites;
        this.currentUser = currentUser;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code enabled} is answered from the site rather than from whether any rows exist,
     * so the DPR can tell "this site does not work that way" from "this site does, and
     * nobody was counted today" — the second is worth printing as a zero, the first is a
     * section that should not appear at all.</p>
     */
    @Override
    public OutsourcedDay outsourced(UUID siteId, LocalDate date) {
        boolean enabled = sites.require(siteId).usesOutsourcedLabour();
        List<SiteLabourCount> rows = labourCounts.findBySiteIdAndCountDate(siteId, date);
        if (rows.isEmpty()) {
            return new OutsourcedDay(date, enabled, 0, List.of());
        }
        UUID orgId = currentUser.currentOrgId();
        Map<UUID, String> skillNames = skillCategories.findByOrgIdOrderByCode(orgId).stream()
                .collect(Collectors.toMap(SkillCategory::getId, SkillCategory::getName));
        Map<UUID, String> contractorNames = contractors
                .findByOrgIdAndDeletedAtIsNullOrderByCode(orgId).stream()
                .collect(Collectors.toMap(LabourContractor::getId, LabourContractor::getName));

        List<OutsourcedGroup> groups = rows.stream()
                .map(row -> new OutsourcedGroup(row.getSkillCategoryId(),
                        skillNames.get(row.getSkillCategoryId()),
                        row.getLabourContractorId(),
                        row.getLabourContractorId() == null
                                ? null : contractorNames.get(row.getLabourContractorId()),
                        row.getHeadCount()))
                .sorted(Comparator.comparing((OutsourcedGroup g) ->
                                g.skillCategoryName() == null ? "" : g.skillCategoryName())
                        .thenComparing(g -> g.labourContractorName() == null
                                ? "" : g.labourContractorName()))
                .toList();

        return new OutsourcedDay(date, enabled,
                groups.stream().mapToInt(OutsourcedGroup::headCount).sum(), groups);
    }

    @Override
    public LabourDay day(UUID siteId, LocalDate date) {
        List<AttendanceRecord> live = records.findLiveForDay(siteId, date);
        Map<UUID, Worker> byWorker = workerIndex(live);

        int present = 0;
        int absent = 0;
        BigDecimal regular = BigDecimal.ZERO;
        BigDecimal overtime = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal unverifiedCost = BigDecimal.ZERO;
        int unverified = 0;

        // Keyed on the pair, and insertion-ordered, so the DPR's labour table comes out in a
        // stable order whatever order the roster was saved in.
        Map<GroupKey, Accumulator> groups = new LinkedHashMap<>();
        List<UUID> boqItemIds = new ArrayList<>();

        for (AttendanceRecord record : live) {
            if (record.getStatus().isPaid()) {
                present++;
            } else {
                absent++;
                // An absent row still exists and is still evidence, but it contributes no
                // hours, no money and no line to the labour table.
                continue;
            }
            regular = regular.add(record.getRegularHours());
            overtime = overtime.add(record.getOvertimeHours());
            cost = cost.add(record.getTotalAmount());
            if (record.getWorkflowStatus() != WorkflowStatus.VERIFIED
                    && record.getWorkflowStatus() != WorkflowStatus.LOCKED) {
                unverifiedCost = unverifiedCost.add(record.getTotalAmount());
                unverified++;
            }

            if (record.getBoqItemId() != null && !boqItemIds.contains(record.getBoqItemId())) {
                boqItemIds.add(record.getBoqItemId());
            }

            Worker worker = byWorker.get(record.getWorkerId());
            GroupKey key = new GroupKey(
                    worker == null ? null : worker.getSkillCategoryId(),
                    worker == null ? null : worker.getLabourContractorId());
            groups.computeIfAbsent(key, unused -> new Accumulator()).add(record);
        }

        return new LabourDay(date, present, absent, regular, overtime, cost, unverifiedCost,
                live.size(), unverified, namedGroups(groups), List.copyOf(boqItemIds));
    }

    @Override
    public LabourPeriod period(UUID siteId, LocalDate from, LocalDate to) {
        List<AttendanceRecord> live =
                records.findLiveForOrgPeriod(currentUser.currentOrgId(), siteId, from, to);

        BigDecimal manDays = BigDecimal.ZERO;
        BigDecimal regular = BigDecimal.ZERO;
        BigDecimal overtime = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal verifiedCost = BigDecimal.ZERO;
        int pending = 0;

        for (AttendanceRecord record : live) {
            if (record.getWorkflowStatus() == WorkflowStatus.SUBMITTED) {
                pending++;
            }
            if (!record.getStatus().isPaid()) {
                continue;
            }
            manDays = manDays.add(record.getStatus() == AttendanceStatus.HALF_DAY
                    ? HALF : BigDecimal.ONE);
            regular = regular.add(record.getRegularHours());
            overtime = overtime.add(record.getOvertimeHours());
            cost = cost.add(record.getTotalAmount());
            if (record.getWorkflowStatus() == WorkflowStatus.VERIFIED
                    || record.getWorkflowStatus() == WorkflowStatus.LOCKED) {
                verifiedCost = verifiedCost.add(record.getTotalAmount());
            }
        }

        long distinctDays = live.stream().map(AttendanceRecord::getAttendanceDate).distinct().count();
        return new LabourPeriod(from, to, manDays, regular, overtime, cost, verifiedCost,
                pending, (int) distinctDays);
    }

    @Override
    public List<DailyCost> dailyCost(UUID siteId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        // Every day in the range gets a bucket, including the empty ones: a trend line with a
        // gap where Sunday was reads as missing data rather than as a day nobody worked.
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.put(day, BigDecimal.ZERO);
        }
        for (AttendanceRecord record
                : records.findLiveForOrgPeriod(currentUser.currentOrgId(), siteId, from, to)) {
            if (!record.getStatus().isPaid()) {
                continue;
            }
            byDay.merge(record.getAttendanceDate(), record.getTotalAmount(), BigDecimal::add);
        }
        return byDay.entrySet().stream()
                .map(entry -> new DailyCost(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * The gap list, not a count. "Eleven days unmarked" sends somebody scrolling a month of
     * calendar; the dates themselves are the thing they can act on.
     */
    @Override
    public List<LocalDate> daysWithoutAttendance(UUID siteId, LocalDate from, LocalDate to) {
        Set<LocalDate> marked = records
                .findLiveForOrgPeriod(currentUser.currentOrgId(), siteId, from, to).stream()
                .map(AttendanceRecord::getAttendanceDate)
                .collect(Collectors.toSet());
        List<LocalDate> gaps = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!marked.contains(day)) {
                gaps.add(day);
            }
        }
        return gaps;
    }

    // ------------------------------------------------------------------ internals

    private Map<UUID, Worker> workerIndex(List<AttendanceRecord> live) {
        Set<UUID> ids = live.stream().map(AttendanceRecord::getWorkerId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return workers.findByIdInAndOrgIdAndDeletedAtIsNull(ids, currentUser.currentOrgId())
                .stream().collect(Collectors.toMap(Worker::getId, Function.identity()));
    }

    /**
     * Puts names on the groups in one pass over each master table, rather than a lookup per
     * row: a busy site has forty rows and six trades.
     */
    private List<LabourGroup> namedGroups(Map<GroupKey, Accumulator> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        UUID orgId = currentUser.currentOrgId();
        Map<UUID, String> skillNames = skillCategories.findByOrgIdOrderByCode(orgId).stream()
                .collect(Collectors.toMap(SkillCategory::getId, SkillCategory::getName));
        Map<UUID, String> contractorNames = contractors
                .findByOrgIdAndDeletedAtIsNullOrderByCode(orgId).stream()
                .collect(Collectors.toMap(LabourContractor::getId, LabourContractor::getName));

        return groups.entrySet().stream()
                .map(entry -> {
                    GroupKey key = entry.getKey();
                    Accumulator sum = entry.getValue();
                    return new LabourGroup(key.skillCategoryId(),
                            key.skillCategoryId() == null
                                    ? null : skillNames.get(key.skillCategoryId()),
                            key.labourContractorId(),
                            key.labourContractorId() == null
                                    ? null : contractorNames.get(key.labourContractorId()),
                            sum.headCount, sum.regularHours, sum.overtimeHours, sum.cost);
                })
                .sorted(Comparator.comparing(group -> group.skillCategoryName() == null
                        ? "￿" : group.skillCategoryName()))
                .toList();
    }

    private record GroupKey(UUID skillCategoryId, UUID labourContractorId) {
        @Override
        public boolean equals(Object other) {
            return other instanceof GroupKey that
                    && Objects.equals(skillCategoryId, that.skillCategoryId)
                    && Objects.equals(labourContractorId, that.labourContractorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skillCategoryId, labourContractorId);
        }
    }

    private static final class Accumulator {
        private int headCount;
        private BigDecimal regularHours = BigDecimal.ZERO;
        private BigDecimal overtimeHours = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;

        private void add(AttendanceRecord record) {
            headCount++;
            regularHours = regularHours.add(record.getRegularHours());
            overtimeHours = overtimeHours.add(record.getOvertimeHours());
            cost = cost.add(record.getTotalAmount());
        }
    }
}
