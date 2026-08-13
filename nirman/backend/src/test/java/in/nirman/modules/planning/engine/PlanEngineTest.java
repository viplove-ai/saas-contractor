package in.nirman.modules.planning.engine;

import in.nirman.modules.planning.engine.PlanInput.CommercialTerms;
import in.nirman.modules.planning.engine.PlanInput.ConsumptionNorm;
import in.nirman.modules.planning.engine.PlanInput.CostBasis;
import in.nirman.modules.planning.engine.PlanInput.LeadTime;
import in.nirman.modules.planning.engine.PlanInput.Milestone;
import in.nirman.modules.planning.engine.PlanInput.Norms;
import in.nirman.modules.planning.engine.PlanInput.ProductivityNorm;
import in.nirman.modules.planning.engine.PlanInput.SequenceNorm;
import in.nirman.modules.planning.engine.PlanInput.WorkItem;
import in.nirman.modules.planning.engine.PlanInput.WorkTypeProfile;
import in.nirman.modules.planning.engine.PlanOutput.Finding;
import in.nirman.modules.planning.engine.PlanOutput.MonthlyCash;
import in.nirman.modules.planning.engine.PlanOutput.MonthlyMaterial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic, held to what it claims.
 *
 * <p>This is the most heavily tested code in the planning module and deliberately so: every
 * figure it produces will be read as advice about money, and there is no reference
 * implementation and no user who can check it by eye. So the tests are written as statements
 * about construction rather than as snapshots — a mason lays about a cubic metre a day, cement
 * is ordered before it is poured, retention comes off every bill — and each one fails with the
 * sentence it was defending.</p>
 */
class PlanEngineTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Nested
    @DisplayName("work and time")
    class WorkAndTime {

        @Test
        @DisplayName("the fewest gangs that meet the date, not the most the front would hold")
        void gangsAreAddedOnlyToMakeTheDeadline() {
            // 500 cum of brickwork is about 550 gang-days at the norm — one gang would run 550
            // days and miss a one-year programme, two gangs finish in 275 and do not. The cap
            // allows six, and putting six on would be a plan nobody would staff.
            PlanOutput roomy = PlanEngine.plan(input(
                    List.of(masonry("500")), List.of(), 365, "0"));
            PlanOutput.Package block = roomy.packages().get(0);
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    block.startDate(), block.endDate());

            assertThat(block.gangs()).as("gangs deployed against a comfortable date").isEqualTo(2);
            assertThat(days).as("days to lay 500 cum with two gangs").isBetween(260L, 290L);
            assertThat(days).as("and the programme fits the time allowed")
                    .isLessThanOrEqualTo(365L);

            // Halve the time and the engine buys the extra crew rather than reporting failure,
            // because the working front still has room for it.
            PlanOutput tight = PlanEngine.plan(input(
                    List.of(masonry("500")), List.of(), 150, "0"));
            assertThat(tight.packages().get(0).gangs())
                    .as("a tighter date is met by deploying more of the front, up to the cap")
                    .isGreaterThan(block.gangs());
            assertThat(tight.findings())
                    .filteredOn(finding -> finding.severity() == Finding.Severity.BLOCKING)
                    .as("and no infeasibility is claimed while the cap has headroom").isEmpty();
        }

        @Test
        @DisplayName("work that cannot fit the time allowed at the crew cap says so")
        void infeasibilityIsTheFinding() {
            // Six months of brickwork into thirty days. No number of gangs inside the cap can
            // do it, and the useful output is the refusal rather than a schedule that pretends.
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("5000")), List.of(), 30, "0"));

            assertThat(plan.findings())
                    .filteredOn(finding -> finding.severity() == Finding.Severity.BLOCKING)
                    .as("a programme that does not fit reports it")
                    .isNotEmpty();
            assertThat(plan.findings().get(0).message()).contains("does not fit");
        }

        @Test
        @DisplayName("masonry does not begin before the concrete it stands on")
        void precedenceIsRespected() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(concrete("200"), masonry("300")), List.of(), 730, "0"));

            PlanOutput.Package rcc = packageFor(plan, "Concrete & RCC");
            PlanOutput.Package masonry = packageFor(plan, "Masonry");
            assertThat(masonry.startDate())
                    .as("masonry starts after the RCC has begun, and not on day one")
                    .isAfter(rcc.startDate());
        }

        @Test
        @DisplayName("a reconciliation placeholder is paid for but never scheduled")
        void syntheticLinesCarryValueAndNoWork() {
            WorkItem placeholder = new WorkItem("UNALLOCATED-1", "Unallocated BOQ balance",
                    "Unallocated BOQ Balance", "Civil Works", BigDecimal.ZERO, null,
                    new BigDecimal("1000000"), true);
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("100"), placeholder), List.of(), 365, "0"));

            assertThat(plan.packages())
                    .as("nothing is scheduled against a line that describes no work")
                    .noneMatch(block -> "Unallocated BOQ Balance".equals(block.category()));
            assertThat(plan.assumptions())
                    .as("and its value is declared rather than silently dropped")
                    .anyMatch(a -> a.subject().contains("productivity norm"));
        }
    }

    @Nested
    @DisplayName("material")
    class Material {

        @Test
        @DisplayName("cement is ordered before the month it is poured in, not during it")
        void procurementLeadsRequirement() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(concrete("300")), List.of(), 365, "0"));

            List<MonthlyMaterial> cement = plan.material().stream()
                    .filter(row -> "CEM-OPC43".equals(row.materialCode()))
                    .toList();
            assertThat(cement).isNotEmpty();

            var firstOrder = cement.stream().filter(row -> row.procureQty().signum() > 0)
                    .map(MonthlyMaterial::month).findFirst().orElseThrow();
            var firstNeed = cement.stream().filter(row -> row.requiredQty().signum() > 0)
                    .map(MonthlyMaterial::month).findFirst().orElseThrow();
            assertThat(firstOrder)
                    .as("the order goes out no later than the month the cement is needed")
                    .isLessThanOrEqualTo(firstNeed);

            // The two curves are the same total quantity seen from two different questions.
            BigDecimal required = cement.stream().map(MonthlyMaterial::requiredQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal procured = cement.stream().map(MonthlyMaterial::procureQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(procured)
                    .as("nothing is ordered that is not needed, and nothing needed goes unordered")
                    .isEqualByComparingTo(required);
        }

        @Test
        @DisplayName("300 cum of concrete needs roughly six bags of cement per cubic metre")
        void quantitiesFollowTheConsumptionNorm() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(concrete("300")), List.of(), 365, "0"));

            BigDecimal cement = plan.material().stream()
                    .filter(row -> "CEM-OPC43".equals(row.materialCode()))
                    .map(MonthlyMaterial::requiredQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(cement).as("300 cum × 6.2 bags").isEqualByComparingTo("1860.000");
        }
    }

    @Nested
    @DisplayName("money")
    class Money {

        @Test
        @DisplayName("the quoted percentage moves every rupee the department will pay")
        void quotedPercentageIsApplied() {
            BigDecimal atPar = billed(PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "0")));
            BigDecimal tenBelow = billed(PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "-10")));

            assertThat(tenBelow)
                    .as("a bid ten percent below the estimate bills ninety percent of it")
                    .isEqualByComparingTo(atPar.multiply(new BigDecimal("0.9"))
                            .setScale(2, java.math.RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("what arrives is the bill less retention and the statutory deductions")
        void netReceiptIsNotGrossBill() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "0"));

            BigDecimal gross = billed(plan);
            BigDecimal net = plan.cash().stream().map(MonthlyCash::netReceived)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 2.5 security deposit + 2 income tax + 2 GST + 1 cess = 7.5%. Compared to the
            // rupee: each bill is rounded on its own, so the total need not equal the total
            // rounded once, and pinning the paisa would be pinning the rounding rather than
            // the deduction.
            assertThat(net).as("net receipts are 92.5% of what was billed")
                    .isCloseTo(gross.multiply(new BigDecimal("0.925")),
                            org.assertj.core.data.Offset.offset(BigDecimal.ONE));
            assertThat(net).as("and the gap is not nothing").isLessThan(gross);
        }

        @Test
        @DisplayName("the funding peak is deeper than the first month, because payment lags")
        void peakIsTheTroughNotTheFirstMonth() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "0"));

            BigDecimal peak = plan.workingCapital().peakFundingRequirement();
            BigDecimal firstMonthOutflow = plan.cash().get(0).totalOutflow();

            assertThat(peak)
                    .as("money keeps going out through the whole payment lag")
                    .isGreaterThan(firstMonthOutflow);
            assertThat(plan.workingCapital().peakMonth()).isNotNull();
        }

        @Test
        @DisplayName("retention is released after the defect liability period, not at handover")
        void retentionIsLockedPastCompletion() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "0"));

            assertThat(plan.workingCapital().retentionReleasedOn())
                    .as("six months past the completion date")
                    .isEqualTo(START.plusDays(365).plusMonths(6));
            assertThat(plan.workingCapital().totalRetentionHeld()).isPositive();
        }

        @Test
        @DisplayName("Clause 7A is reported, because until it is met the inflow is zero")
        void clause7aIsSurfaced() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("300")), List.of(), 365, "0"));

            assertThat(plan.findings())
                    .anyMatch(finding -> finding.message().contains("Clause 7A"));
        }
    }

    @Nested
    @DisplayName("milestones")
    class Milestones {

        @Test
        @DisplayName("a milestone the programme misses names the percentage at risk")
        void shortfallIsReported() {
            // Half the contract due in a tenth of the time.
            Milestone impossible = new Milestone(1, "50% of Tendered Amount", 30,
                    new BigDecimal("50"), new BigDecimal("2.5"), false);
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("400")), List.of(impossible), 365, "0"));

            assertThat(plan.phases()).hasSize(1);
            assertThat(plan.phases().get(0).onTarget()).isFalse();
            assertThat(plan.findings())
                    .anyMatch(finding -> finding.message().contains("Milestone 1 is short")
                            && finding.message().contains("2.5%"));
        }

        @Test
        @DisplayName("a physical milestone asserts no percentage it cannot read")
        void physicalMilestonesKeepTheirWords() {
            Milestone physical = new Milestone(1,
                    "Civil Work: excavation, lean concrete, RCC upto plinth level beams", 200,
                    null, new BigDecimal("0.5"), true);
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("100")), List.of(physical), 365, "0"));

            assertThat(plan.phases().get(0).description()).contains("plinth level beams");
            assertThat(plan.phases().get(0).targetPercent()).isNull();
            // Nothing is claimed against it, so it is not reported as missed either.
            assertThat(plan.phases().get(0).onTarget()).isTrue();
        }

        @Test
        @DisplayName("no milestone table is a note, not a silent default")
        void absentMilestonesAreDeclared() {
            PlanOutput plan = PlanEngine.plan(input(
                    List.of(masonry("100")), List.of(), 365, "0"));

            assertThat(plan.findings())
                    .anyMatch(finding -> finding.message().contains("stated no milestones"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static PlanOutput.Package packageFor(PlanOutput plan, String category) {
        return plan.packages().stream()
                .filter(block -> category.equals(block.category()))
                .findFirst().orElseThrow(() -> new AssertionError("no package for " + category));
    }

    private static BigDecimal billed(PlanOutput plan) {
        return plan.cash().stream().map(MonthlyCash::grossBilled)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static WorkItem masonry(String quantity) {
        return new WorkItem("1.1", "Brick work in cement mortar", "Masonry", "Civil Works",
                new BigDecimal(quantity), "CUM",
                new BigDecimal(quantity).multiply(new BigDecimal("6000")), false);
    }

    private static WorkItem concrete(String quantity) {
        return new WorkItem("2.1", "RCC M25 in foundations", "Concrete & RCC", "Civil Works",
                new BigDecimal(quantity), "CUM",
                new BigDecimal(quantity).multiply(new BigDecimal("8000")), false);
    }

    private static PlanInput input(List<WorkItem> items, List<Milestone> milestones,
                                   int allowedDays, String quotedPercent) {
        BigDecimal contractValue = items.stream().map(WorkItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PlanInput(
                new WorkTypeProfile("BUILDING_NEW", "Building - new construction", true,
                        new BigDecimal("8"), Map.of()),
                START, allowedDays, new BigDecimal(quotedPercent), items, milestones,
                new CommercialTerms(contractValue, new BigDecimal("5"), new BigDecimal("2.5"),
                        Map.of("Civil Works", new BigDecimal("500000")), true, 45,
                        new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("1"),
                        BigDecimal.ZERO, 6, new BigDecimal("50000")),
                new CostBasis(Map.of("MASON", new BigDecimal("800"),
                        "HELPER", new BigDecimal("500"),
                        "CARPENTER", new BigDecimal("800")),
                        new BigDecimal("500"), 26, new BigDecimal("120000"),
                        new BigDecimal("250000"), new BigDecimal("60000"), new BigDecimal("1.2")),
                norms());
    }

    private static Norms norms() {
        return new Norms(
                List.of(new ProductivityNorm("Masonry", null, "MASON", true, "CUM",
                                new BigDecimal("0.9")),
                        new ProductivityNorm("Masonry", null, "HELPER", false, "CUM",
                                new BigDecimal("1.1")),
                        new ProductivityNorm("Concrete & RCC", null, "MASON", true, "CUM",
                                new BigDecimal("0.2")),
                        new ProductivityNorm("Concrete & RCC", null, "HELPER", false, "CUM",
                                new BigDecimal("1.3")),
                        // A sub-typed norm prices an operation inside a category and must not
                        // be added on top of the category's own rows.
                        new ProductivityNorm("Concrete & RCC", "Formwork", "CARPENTER", true,
                                "SQM", new BigDecimal("0.1"))),
                List.of(new SequenceNorm("Concrete & RCC", 4, new BigDecimal("50"), 3, true),
                        new SequenceNorm("Masonry", 5, new BigDecimal("40"), 6, false)),
                List.of(new ConsumptionNorm("Concrete & RCC", null, "CEM-OPC43", "OPC 43 Cement",
                                "CUM", new BigDecimal("6.2"), new BigDecimal("400")),
                        new ConsumptionNorm("Masonry", null, "BRICK-1C", "Brick Class 1",
                                "CUM", new BigDecimal("500"), new BigDecimal("8"))),
                Map.of("CEM-OPC43", new LeadTime(3, 2, 90, true),
                        "BRICK-1C", new LeadTime(7, 3, null, true)));
    }
}
