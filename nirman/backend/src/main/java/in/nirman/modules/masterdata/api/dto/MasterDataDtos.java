package in.nirman.modules.masterdata.api.dto;

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

    public record VendorResponse(
            UUID id, String code, String name, Vendor.Type vendorType, String contactPerson,
            String mobile, String email, String address, String gstin, String pan,
            String bankAccountNo, String bankIfsc, int creditDays, BigDecimal openingBalance,
            boolean active, Long version) {
    }

    public record CreateVendorRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull Vendor.Type vendorType,
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

    // ------------------------------------------------------------------ labour contractors

    public record LabourContractorResponse(
            UUID id, String code, String name, String contactPerson, String mobile, String email,
            String address, String gstin, String pan, String bankAccountNo, String bankIfsc,
            boolean active, Long version) {
    }

    public record CreateLabourContractorRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address,
            @Size(max = 15) String gstin,
            @Size(max = 10) String pan,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc) {
    }

    public record UpdateLabourContractorRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 150) String contactPerson,
            @Size(max = 20) String mobile,
            @Email @Size(max = 150) String email,
            String address,
            @Size(max = 15) String gstin,
            @Size(max = 10) String pan,
            @Size(max = 30) String bankAccountNo,
            @Size(max = 15) String bankIfsc,
            boolean active,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ materials

    public record MaterialResponse(
            UUID id, String code, String name, UUID categoryId, UUID baseUnitId, String hsnCode,
            BigDecimal gstPercent, BigDecimal minStockLevel, BigDecimal standardRate,
            UUID preferredVendorId, boolean consumable, boolean active, Long version) {
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

    public record ExpenseCategoryResponse(
            UUID id, String code, String name, UUID parentId, boolean materialPurchase,
            boolean labourPayment, boolean requiresVendor, boolean active, int sortOrder) {
    }

    public record SaveExpenseCategoryRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9._-]+") String code,
            @NotBlank @Size(max = 120) String name,
            UUID parentId,
            boolean materialPurchase,
            boolean labourPayment,
            boolean requiresVendor,
            @Min(0) int sortOrder) {
    }
}
