package in.nirman.modules.treasury;

import in.nirman.modules.treasury.domain.ProjectSecurity.Instrument;
import in.nirman.modules.treasury.domain.ProjectSecurity.Type;
import in.nirman.modules.treasury.service.SecurityProposer;
import in.nirman.modules.treasury.service.SecurityProposer.ContractFacts;
import in.nirman.modules.treasury.service.SecurityProposer.NoticeTerms;
import in.nirman.modules.treasury.service.SecurityProposer.Proposal;
import in.nirman.modules.treasury.service.SecurityProposer.WorkNature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four rules, as arithmetic. Every number below is off the CPWD standard form, and the
 * one-crore worked example is the one V33's own comment quotes from a notice.
 */
class SecurityProposerTest {

    private static final BigDecimal CRORE = new BigDecimal("10000000.00");

    private static Optional<Proposal> of(List<Proposal> proposals, Type type) {
        return proposals.stream().filter(p -> p.type() == type).findFirst();
    }

    private static ContractFacts atPar() {
        return new ContractFacts(CRORE, CRORE, WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);
    }

    @Test
    @DisplayName("earnest money is 2.5% of the estimate, and comes back when the work starts")
    void earnestMoney() {
        List<Proposal> proposals = SecurityProposer.propose(atPar());
        Proposal emd = of(proposals, Type.EMD).orElseThrow();

        assertThat(emd.amount()).isEqualByComparingTo("250000.00");
        assertThat(emd.instrument()).isEqualTo(Instrument.FDR);
        assertThat(emd.expectedReleaseOn()).isEqualTo(LocalDate.of(2026, 1, 12));
    }

    /**
     * V41 dated this off the work's start rather than the allotment letter, which nobody
     * entered. A contract with no start date recorded proposes no release date at all —
     * the blank is deliberate, since a wrong one stops the office chasing the FDR.
     */
    @Test
    @DisplayName("with no start date yet, the earnest money is proposed without a release date")
    void earnestMoneyBeforeWorkStarts() {
        ContractFacts facts = new ContractFacts(CRORE, CRORE, WorkNature.CONSTRUCTION,
                null, null, 12, NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(facts), Type.EMD).orElseThrow()
                .expectedReleaseOn()).isNull();
    }

    @Test
    @DisplayName("the notice's own earnest money figure beats the standard percentage")
    void noticeEmdWins() {
        NoticeTerms notice = new NoticeTerms(new BigDecimal("241500"), null, null, null, null, null);
        ContractFacts facts = new ContractFacts(CRORE, CRORE, WorkNature.CONSTRUCTION,
                LocalDate.of(2026, 1, 5), null, 12, notice);

        Proposal emd = of(SecurityProposer.propose(facts), Type.EMD).orElseThrow();
        assertThat(emd.amount()).isEqualByComparingTo("241500.00");
        assertThat(emd.basis()).contains("As stated in the notice");
    }

    @Test
    @DisplayName("the guarantee stands on the estimate, not the bid — a low bid does not shrink it")
    void guaranteeIgnoresALowBid() {
        // Bid 30% below: the contract is 70 lakh, and the guarantee is still 5% of the crore.
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("7000000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        Proposal pg = of(SecurityProposer.propose(facts), Type.PERFORMANCE_GUARANTEE).orElseThrow();
        assertThat(pg.amount()).isEqualByComparingTo("500000.00");
        assertThat(pg.basis()).contains("higher of the two");
    }

    @Test
    @DisplayName("a bid above the estimate moves the guarantee up with the contract")
    void guaranteeFollowsAHigherContract() {
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("12000000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(facts), Type.PERFORMANCE_GUARANTEE).orElseThrow()
                .amount()).isEqualByComparingTo("600000.00");
    }

    @Test
    @DisplayName("a bid 30% below adds ten lakh of additional guarantee — V33's worked example")
    void additionalGuaranteeOnADeepBid() {
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("7000000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        Proposal apg = of(SecurityProposer.propose(facts), Type.ADDITIONAL_PG).orElseThrow();
        // 80% of a crore is 80 lakh; the bid was 70 lakh; the difference is 10 lakh.
        assertThat(apg.amount()).isEqualByComparingTo("1000000.00");
        assertThat(apg.expectedReleaseOn()).isEqualTo(LocalDate.of(2028, 6, 30));
    }

    @Test
    @DisplayName("a bid inside the threshold triggers no additional guarantee at all, not a zero one")
    void noAdditionalGuaranteeOnAShallowBid() {
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("8500000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(facts), Type.ADDITIONAL_PG)).isEmpty();
    }

    @Test
    @DisplayName("a department levying a flat percentage of the bid is followed instead")
    void additionalGuaranteeByPercentOfBid() {
        NoticeTerms notice = new NoticeTerms(null, null, null, new BigDecimal("80"),
                "PERCENT_OF_BID", new BigDecimal("3"));
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("7000000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, notice);

        assertThat(of(SecurityProposer.propose(facts), Type.ADDITIONAL_PG).orElseThrow()
                .amount()).isEqualByComparingTo("210000.00");
    }

    @Test
    @DisplayName("a construction guarantee runs a year past completion, a maintenance one six months")
    void guaranteeReleaseFollowsTheWorkNature() {
        LocalDate completion = LocalDate.of(2027, 6, 30);
        ContractFacts construction = atPar();
        ContractFacts maintenance = new ContractFacts(CRORE, CRORE, WorkNature.MAINTENANCE, LocalDate.of(2026, 1, 12), completion, 12,
                NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(construction), Type.PERFORMANCE_GUARANTEE)
                .orElseThrow().expectedReleaseOn()).isEqualTo(LocalDate.of(2028, 6, 30));
        assertThat(of(SecurityProposer.propose(maintenance), Type.PERFORMANCE_GUARANTEE)
                .orElseThrow().expectedReleaseOn()).isEqualTo(LocalDate.of(2027, 12, 30));
    }

    @Test
    @DisplayName("no work nature recorded proposes no release date, rather than guessing at one")
    void noWorkNatureNoDate() {
        ContractFacts facts = new ContractFacts(CRORE, CRORE, null, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(facts), Type.PERFORMANCE_GUARANTEE).orElseThrow()
                .expectedReleaseOn()).isNull();
    }

    @Test
    @DisplayName("the security deposit is 2.5% of the tendered amount, withheld and never lodged")
    void securityDeposit() {
        ContractFacts facts = new ContractFacts(CRORE, new BigDecimal("7000000.00"),
                WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, NoticeTerms.NONE);

        Proposal sd = of(SecurityProposer.propose(facts), Type.SECURITY_DEPOSIT).orElseThrow();
        // Off the contract, not the estimate: it is deducted from bills, and the bills are the
        // contract's.
        assertThat(sd.amount()).isEqualByComparingTo("175000.00");
        assertThat(sd.instrument()).isEqualTo(Instrument.BILL_RETENTION);
        assertThat(sd.expectedReleaseOn()).isEqualTo(LocalDate.of(2028, 6, 30));
    }

    @Test
    @DisplayName("no defect liability period recorded, no release date for the retention")
    void depositWithoutADefectLiabilityPeriod() {
        ContractFacts facts = new ContractFacts(CRORE, CRORE, WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), null, NoticeTerms.NONE);

        assertThat(of(SecurityProposer.propose(facts), Type.SECURITY_DEPOSIT).orElseThrow()
                .expectedReleaseOn()).isNull();
    }

    @Test
    @DisplayName("a contract with no figures at all proposes zeroes and says why, rather than failing")
    void nothingRecorded() {
        ContractFacts facts = new ContractFacts(null, null, null, null, null, null,
                NoticeTerms.NONE);

        List<Proposal> proposals = SecurityProposer.propose(facts);
        assertThat(proposals).hasSize(3);
        assertThat(of(proposals, Type.EMD).orElseThrow().basis())
                .contains("cannot be worked out");
        assertThat(of(proposals, Type.ADDITIONAL_PG)).isEmpty();
    }

    @Test
    @DisplayName("a notice's own percentages beat the standard form, and the basis says which was used")
    void noticePercentagesWin() {
        NoticeTerms notice = new NoticeTerms(null, new BigDecimal("3"), new BigDecimal("5"),
                null, null, null);
        ContractFacts facts = new ContractFacts(CRORE, CRORE, WorkNature.CONSTRUCTION, LocalDate.of(2026, 1, 12),
                LocalDate.of(2027, 6, 30), 12, notice);

        List<Proposal> proposals = SecurityProposer.propose(facts);
        assertThat(of(proposals, Type.PERFORMANCE_GUARANTEE).orElseThrow().amount())
                .isEqualByComparingTo("300000.00");
        assertThat(of(proposals, Type.PERFORMANCE_GUARANTEE).orElseThrow().basis())
                .contains("as stated in the notice");
        assertThat(of(proposals, Type.SECURITY_DEPOSIT).orElseThrow().amount())
                .isEqualByComparingTo("500000.00");
        assertThat(of(proposals, Type.EMD).orElseThrow().basis())
                .contains("standard form");
    }
}
