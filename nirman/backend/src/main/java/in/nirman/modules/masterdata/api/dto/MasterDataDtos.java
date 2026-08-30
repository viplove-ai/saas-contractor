package in.nirman.modules.masterdata.api.dto;

import in.nirman.common.CostAllocation;
import in.nirman.modules.masterdata.domain.Vendor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for every master-data aggregate. */
public final class MasterDataDtos {

    private MasterDataDtos() {
    }

    // ------------------------------------------------------------------ units

    public record UnitResponse(UUID id, String code, String name, int decimalPlaces, boolean active) {
    }

    public record SaveUnitRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Z0-9._-]+") String code,
            @NotBlank @Size(max = 60) String name,
            @Min(0) @Max(8) int decimalPlaces) {
    }

    // ------------------------------------------------------------------ skill categories

    public record SkillCategoryResponse(UUID id, String code, String name, boolean skilled,
                                        boolean active) {
    }

    public record SaveSkillCategoryRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9._-]+") String code,
            @NotBlank @Size(max = 100) String name,
            boolean skilled) {
    }

    // ------------------------------------------------------------------ material categories

    public record MaterialCategoryResponse(UUID id, String code, String name, UUID parentId,
                                           boolean active) {
    }

    public record SaveMaterialCategoryRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9._-]+") String code,
            @NotBlank @Size(max = 120) String name,
            UUID parentId) {
    }

    // ------------------------------------------------------------------ vendors

    /**
     * @param provisional the row was named from the field — what he is called, what he
     *                    supplies and how to reach him — and the office has not put its own
     *                    numbers on it yet: no GSTIN, no bank account, no credit period.
     */
    public record VendorResponse(
            UUID id, String code, String name, Vendor.Type vendorType,
            /** What OTHER means for this firm. Absent on every other kind. */
            String suppliesNote,
            String contactPerson,
            String mobile, String email, String address, String gstin, String pan,
            String bankAccountNo, String bankIfsc, int creditDays, BigDecimal openingBalance,
            boolean active, boolean provisional, Long version) {
    }

    /**
     * @param code left out by every screen. A short code is a filing decision the office was
     *             never really making — asked for it, somebody typed the firm's initials and
     *             the next person typed the town — so the server derives one from what he
     *             supplies and what he is called. Still accepted, because an organisation
     *             migrating its own register has codes that already mean something and
     *             throwing them away would break the paper trail.
     */
    public record CreateVendorRequest(
            @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]*") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull Vendor.Type vendorType,
            /** Required when the kind is OTHER, refused on any other. See V53. */
            @Size(max = 120) String suppliesNote,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address,
            @Size(max = 15) String gstin,
            @Size(max = 10) String pan,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc,
            @Min(0) @Max(365) int creditDays,
            @Digits(integer = 16, fraction = 2) BigDecimal openingBalance) {
    }

    public record UpdateVendorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Vendor.Type vendorType,
            @Size(max = 120) String suppliesNote,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address,
            @Size(max = 15) String gstin,
            @Size(max = 10) String pan,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc,
            @Min(0) @Max(365) int creditDays,
            boolean active,
            @NotNull Long version) {
    }

    /**
     * A supplier named at the gate, with only what the man watching the lorry unload knows.
     *
     * <p>His name, what he supplies and how to reach him. No GSTIN, no PAN, no bank account
     * and no credit period, on purpose: those are the office's, and a tax number guessed at a
     * gate is one that money is later paid against. No active flag either — a supplier being
     * named is a supplier being used, and taking one off the register is the office's act.</p>
     */
    public record NameVendorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Vendor.Type vendorType,
            @Size(max = 120) String suppliesNote,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address) {
    }

    /**
     * The same act a day later: what the field said about a supplier, corrected by the field.
     *
     * <p>The same fields it could name him with and not one more. A firm changes the man who
     * answers the phone and the number he answers on far more often than it changes its
     * GSTIN, and the person who finds that out is the one standing in front of him.</p>
     */
    public record CorrectFieldVendorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Vendor.Type vendorType,
            @Size(max = 120) String suppliesNote,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ labour contractors


    // ------------------------------------------------------------------ materials

    /**
     * @param provisional the row was named from the field on a receipt and nobody has vetted
     *                    it — no rate, no HSN, and possibly a second name for something the
     *                    catalogue already holds.
     */
    /**
     * @param altUnitIds the other units this material may be booked in — the ones it has a
     *                   conversion for. Carried on the row because a picker that offers
     *                   every unit in the system offers units the server will refuse, and it
     *                   refuses them after the whole delivery has been typed.
     */
    public record MaterialResponse(
            UUID id, String code, String name, UUID categoryId, UUID baseUnitId, String hsnCode,
            BigDecimal gstPercent, BigDecimal minStockLevel, BigDecimal standardRate,
            UUID preferredVendorId, boolean consumable, boolean active, boolean provisional,
            List<UUID> altUnitIds,
            Long version) {
    }

    /**
     * A material named at the gate, with only what the man holding the challan actually knows.
     *
     * <p>No rate, no HSN code and no GST percentage, on purpose: those are the office's to
     * set, and a guess typed at a gate becomes a figure somebody later costs work against.</p>
     */
    public record AddFieldMaterialRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID baseUnitId) {
    }

    /**
     * The same act a day later: the name the field gave a material, corrected by the field.
     *
     * <p>A name and a version, and deliberately nothing else — not even the unit. Naming a
     * thing wrong is the ordinary mistake ("celment", "TMT 12 mm" against the 16 mm bars);
     * changing what it is measured in re-reads every quantity ever booked against it, so 50
     * bags become 50 kilogrammes without a single stock row moving. That correction is the
     * office's, and today not even the office makes it by editing.</p>
     */
    public record CorrectFieldMaterialRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Long version) {
    }

    public record CreateMaterialRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 200) String name,
            UUID categoryId,
            @NotNull UUID baseUnitId,
            @Size(max = 10) String hsnCode,
            @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal gstPercent,
            @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal minStockLevel,
            @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal standardRate,
            UUID preferredVendorId,
            boolean consumable) {
    }

    public record UpdateMaterialRequest(
            @NotBlank @Size(max = 200) String name,
            UUID categoryId,
            @Size(max = 10) String hsnCode,
            @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal gstPercent,
            @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal minStockLevel,
            @PositiveOrZero @Digits(integer = 14, fraction = 4) BigDecimal standardRate,
            UUID preferredVendorId,
            boolean consumable,
            boolean active,
            @NotNull Long version) {
    }

    public record ConversionResponse(UUID id, UUID materialId, UUID altUnitId,
                                     BigDecimal factorToBase) {
    }

    public record SaveConversionRequest(
            @NotNull UUID altUnitId,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 10, fraction = 8) BigDecimal factorToBase) {
    }

    // ------------------------------------------------------------------ expense categories

    /**
     * @param provisional named from a site while booking an expense, and nobody has vetted
     *                    it — neither cost flag decided, and quite possibly a second name for
     *                    a head the taxonomy already carries.
     */
    public record ExpenseCategoryResponse(
            UUID id, String code, String name, UUID parentId, boolean materialPurchase,
            boolean labourPayment, boolean requiresVendor, boolean active, boolean provisional,
            /** What an expense under this head proposes to the approver: the site's, or the
             *  organisation's. Never a split, which is an amount and so a fact about a bill. */
            CostAllocation defaultAllocation,
            int sortOrder) {
    }

    /**
     * An expense head named at the site, with the only thing the man holding the bill knows.
     *
     * <p>No flags and no parent, on purpose: whether a head's rows are inventory rather than
     * cost, or a wage disbursement rather than a new cost, is the office's to decide, and
     * getting it wrong double-counts a month.</p>
     */
    public record NameExpenseCategoryRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record SaveExpenseCategoryRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9._-]+") String code,
            @NotBlank @Size(max = 120) String name,
            UUID parentId,
            boolean materialPurchase,
            boolean labourPayment,
            boolean requiresVendor,
            /** Null means the site's, which is what all but the office heads are. */
            CostAllocation defaultAllocation,
            @Min(0) int sortOrder) {
    }
}
