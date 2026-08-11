package in.nirman.modules.masterdata.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.DocumentNumberService.DocType;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.AddFieldMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ConversionResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CreateLabourContractorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CreateMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CreateVendorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ExpenseCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.LabourContractorResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveConversionRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveExpenseCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveMaterialCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveSkillCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveUnitRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SkillCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UnitResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UpdateLabourContractorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UpdateMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UpdateVendorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.VendorResponse;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.domain.LabourContractor;
import in.nirman.modules.masterdata.domain.Material;
import in.nirman.modules.masterdata.domain.MaterialCategory;
import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.mapper.MasterDataMapper;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.masterdata.repository.LabourContractorRepository;
import in.nirman.modules.masterdata.repository.MaterialCategoryRepository;
import in.nirman.modules.masterdata.repository.MaterialRepository;
import in.nirman.modules.masterdata.repository.MaterialUnitConversionRepository;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.modules.masterdata.repository.UnitRepository;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All master-data CRUD behind two permissions: {@code masterdata:read} for every role,
 * {@code masterdata:write} for admin. One service because the aggregates share exactly one
 * behaviour — org-scoped code-unique reference rows — and none has business logic yet.
 * The first aggregate that grows real rules (vendors with balances in Phase 5) moves out.
 */
@Service
@Transactional
@PreAuthorize("hasAuthority('masterdata:read')")
public class MasterDataService {

    private final UnitRepository units;
    private final SkillCategoryRepository skillCategories;
    private final MaterialCategoryRepository materialCategories;
    private final VendorRepository vendors;
    private final LabourContractorRepository contractors;
    private final MaterialRepository materials;
    private final MaterialUnitConversionRepository conversions;
    private final ExpenseCategoryRepository expenseCategories;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final DocumentNumberService documentNumbers;
    private final MasterDataMapper mapper;

    public MasterDataService(UnitRepository units,
                             SkillCategoryRepository skillCategories,
                             MaterialCategoryRepository materialCategories,
                             VendorRepository vendors,
                             LabourContractorRepository contractors,
                             MaterialRepository materials,
                             MaterialUnitConversionRepository conversions,
                             ExpenseCategoryRepository expenseCategories,
                             CurrentUserProvider currentUser,
                             AuditService audit,
                             DocumentNumberService documentNumbers,
                             MasterDataMapper mapper) {
        this.units = units;
        this.skillCategories = skillCategories;
        this.materialCategories = materialCategories;
        this.vendors = vendors;
        this.contractors = contractors;
        this.materials = materials;
        this.conversions = conversions;
        this.expenseCategories = expenseCategories;
        this.currentUser = currentUser;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ units

    @Transactional(readOnly = true)
    public List<UnitResponse> listUnits() {
        return units.findByOrgIdOrderByCode(orgId()).stream().map(mapper::toResponse).toList();
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public UnitResponse createUnit(SaveUnitRequest request) {
        requireFreeCode(units.existsByOrgIdAndCode(orgId(), request.code()), "Unit", request.code());
        Unit unit = new Unit(orgId(), request.code(), request.name(), request.decimalPlaces());
        units.save(unit);
        recordCreate("UNIT", unit.getId(), unit.getCode());
        return mapper.toResponse(unit);
    }

    // ------------------------------------------------------------------ skill categories

    @Transactional(readOnly = true)
    public List<SkillCategoryResponse> listSkillCategories() {
        return skillCategories.findByOrgIdOrderByCode(orgId()).stream()
                .map(mapper::toResponse).toList();
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public SkillCategoryResponse createSkillCategory(SaveSkillCategoryRequest request) {
        requireFreeCode(skillCategories.existsByOrgIdAndCode(orgId(), request.code()),
                "Skill category", request.code());
        SkillCategory category = new SkillCategory(orgId(), request.code(), request.name(),
                request.skilled());
        skillCategories.save(category);
        recordCreate("SKILL_CATEGORY", category.getId(), category.getCode());
        return mapper.toResponse(category);
    }

    // ------------------------------------------------------------------ material categories

    @Transactional(readOnly = true)
    public List<MaterialCategoryResponse> listMaterialCategories() {
        return materialCategories.findByOrgIdOrderByCode(orgId()).stream()
                .map(mapper::toResponse).toList();
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public MaterialCategoryResponse createMaterialCategory(SaveMaterialCategoryRequest request) {
        requireFreeCode(materialCategories.existsByOrgIdAndCode(orgId(), request.code()),
                "Material category", request.code());
        MaterialCategory category = new MaterialCategory(orgId(), request.code(), request.name(),
                request.parentId());
        materialCategories.save(category);
        recordCreate("MATERIAL_CATEGORY", category.getId(), category.getCode());
        return mapper.toResponse(category);
    }

    // ------------------------------------------------------------------ vendors

    @Transactional(readOnly = true)
    public PageResponse<VendorResponse> listVendors(Vendor.Type type, Boolean active, String q,
                                                    Pageable pageable) {
        return PageResponse.from(vendors.search(orgId(), type, active, emptyToNull(q), pageable),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public VendorResponse getVendor(UUID id) {
        return mapper.toResponse(requireVendor(id));
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public VendorResponse createVendor(CreateVendorRequest request) {
        requireFreeCode(vendors.existsByOrgIdAndCode(orgId(), request.code()), "Vendor",
                request.code());
        Vendor vendor = new Vendor(orgId(), request.code(), request.name(), request.vendorType());
        vendor.setContactPerson(request.contactPerson());
        vendor.setMobile(request.mobile());
        vendor.setEmail(request.email());
        vendor.setAddress(request.address());
        vendor.setGstin(request.gstin());
        vendor.setPan(request.pan());
        vendor.setBankAccountNo(request.bankAccountNo());
        vendor.setBankIfsc(request.bankIfsc());
        vendor.setCreditDays(request.creditDays());
        if (request.openingBalance() != null) {
            vendor.setOpeningBalance(request.openingBalance());
        }
        vendors.save(vendor);
        recordCreate("VENDOR", vendor.getId(), vendor.getCode());
        return mapper.toResponse(vendor);
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public VendorResponse updateVendor(UUID id, UpdateVendorRequest request) {
        Vendor vendor = requireVendor(id);
        requireVersion(vendor.getVersion(), request.version(), "Vendor", id);
        vendor.setName(request.name());
        vendor.setVendorType(request.vendorType());
        vendor.setContactPerson(request.contactPerson());
        vendor.setMobile(request.mobile());
        vendor.setEmail(request.email());
        vendor.setAddress(request.address());
        vendor.setGstin(request.gstin());
        vendor.setPan(request.pan());
        vendor.setBankAccountNo(request.bankAccountNo());
        vendor.setBankIfsc(request.bankIfsc());
        vendor.setCreditDays(request.creditDays());
        vendor.setActive(request.active());
        audit.record("VENDOR", vendor.getId(), "UPDATE", null,
                Map.of("name", vendor.getName(), "active", vendor.isActive()), null);
        return mapper.toResponse(vendor);
    }

    // ------------------------------------------------------------------ labour contractors

    @Transactional(readOnly = true)
    public PageResponse<LabourContractorResponse> listContractors(String q, Pageable pageable) {
        return PageResponse.from(contractors.search(orgId(), emptyToNull(q), pageable),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public LabourContractorResponse getContractor(UUID id) {
        return mapper.toResponse(requireContractor(id));
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public LabourContractorResponse createContractor(CreateLabourContractorRequest request) {
        requireFreeCode(contractors.existsByOrgIdAndCode(orgId(), request.code()),
                "Labour contractor", request.code());
        LabourContractor contractor = new LabourContractor(orgId(), request.code(), request.name());
        contractor.setContactPerson(request.contactPerson());
        contractor.setMobile(request.mobile());
        contractor.setEmail(request.email());
        contractor.setAddress(request.address());
        contractor.setGstin(request.gstin());
        contractor.setPan(request.pan());
        contractor.setBankAccountNo(request.bankAccountNo());
        contractor.setBankIfsc(request.bankIfsc());
        contractors.save(contractor);
        recordCreate("LABOUR_CONTRACTOR", contractor.getId(), contractor.getCode());
        return mapper.toResponse(contractor);
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public LabourContractorResponse updateContractor(UUID id, UpdateLabourContractorRequest request) {
        LabourContractor contractor = requireContractor(id);
        requireVersion(contractor.getVersion(), request.version(), "Labour contractor", id);
        contractor.setName(request.name());
        contractor.setContactPerson(request.contactPerson());
        contractor.setMobile(request.mobile());
        contractor.setEmail(request.email());
        contractor.setAddress(request.address());
        contractor.setGstin(request.gstin());
        contractor.setPan(request.pan());
        contractor.setBankAccountNo(request.bankAccountNo());
        contractor.setBankIfsc(request.bankIfsc());
        contractor.setActive(request.active());
        audit.record("LABOUR_CONTRACTOR", contractor.getId(), "UPDATE", null,
                Map.of("name", contractor.getName(), "active", contractor.isActive()), null);
        return mapper.toResponse(contractor);
    }

    // ------------------------------------------------------------------ materials

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> listMaterials(UUID categoryId, Boolean active, String q,
                                                        Pageable pageable) {
        return PageResponse.from(
                materials.search(orgId(), categoryId, active, emptyToNull(q), pageable),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialResponse getMaterial(UUID id) {
        return mapper.toResponse(requireMaterial(id));
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public MaterialResponse createMaterial(CreateMaterialRequest request) {
        requireFreeCode(materials.existsByOrgIdAndCode(orgId(), request.code()), "Material",
                request.code());
        units.findById(request.baseUnitId())
                .filter(u -> u.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("Unit", request.baseUnitId()));
        Material material = new Material(orgId(), request.code(), request.name(),
                request.baseUnitId());
        applyMaterialFields(material, request.categoryId(), request.hsnCode(), request.gstPercent(),
                request.minStockLevel(), request.standardRate(), request.preferredVendorId(),
                request.consumable());
        materials.save(material);
        recordCreate("MATERIAL", material.getId(), material.getCode());
        return mapper.toResponse(material);
    }

    /**
     * A material named at the gate, because the lorry does not wait for the office.
     *
     * <p>The storekeeper cannot book a delivery of something the catalogue has never heard
     * of — a stock transaction keys on a material id — so this is the alternative to sending
     * him away. What he gets is a real material with a generated code and nothing else
     * filled in, marked {@code provisional} so the office can see it was named rather than
     * decided.</p>
     *
     * <p>An existing material with the same name is <b>returned rather than duplicated</b>.
     * Two rows for one cement would split its stock into two balances, and neither would be
     * the amount in the shed. That check is the whole reason this is not simply
     * {@code createMaterial} with fewer fields.</p>
     */
    @PreAuthorize("hasAuthority('masterdata:provisional')")
    public MaterialResponse addFieldMaterial(AddFieldMaterialRequest request) {
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BusinessException("material.name-required",
                    "A material needs a name to be booked against.");
        }
        List<Material> existing = materials.findByName(orgId(), name);
        if (!existing.isEmpty()) {
            return mapper.toResponse(existing.get(0));
        }
        units.findById(request.baseUnitId())
                .filter(u -> u.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("Unit", request.baseUnitId()));

        Material material = new Material(orgId(),
                documentNumbers.next(orgId(), DocType.MATERIAL, LocalDate.now()),
                name, request.baseUnitId());
        material.setProvisional(true);
        materials.save(material);
        recordCreate("MATERIAL", material.getId(), material.getCode());
        return mapper.toResponse(material);
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public MaterialResponse updateMaterial(UUID id, UpdateMaterialRequest request) {
        Material material = requireMaterial(id);
        requireVersion(material.getVersion(), request.version(), "Material", id);
        material.setName(request.name());
        // Editing the row is the act of vetting it: the office has now looked at the name the
        // field typed and said what it is. Leaving the flag on would keep it forever on a list
        // of things to tidy up that somebody has already tidied.
        material.setProvisional(false);
        applyMaterialFields(material, request.categoryId(), request.hsnCode(), request.gstPercent(),
                request.minStockLevel(), request.standardRate(), request.preferredVendorId(),
                request.consumable());
        material.setActive(request.active());
        audit.record("MATERIAL", material.getId(), "UPDATE", null,
                Map.of("name", material.getName(), "active", material.isActive()), null);
        return mapper.toResponse(material);
    }

    @Transactional(readOnly = true)
    public List<ConversionResponse> listConversions(UUID materialId) {
        requireMaterial(materialId);
        return conversions.findByMaterialId(materialId).stream().map(mapper::toResponse).toList();
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public ConversionResponse addConversion(UUID materialId, SaveConversionRequest request) {
        Material material = requireMaterial(materialId);
        units.findById(request.altUnitId())
                .filter(u -> u.getOrgId().equals(orgId()))
                .orElseThrow(() -> BusinessException.notFound("Unit", request.altUnitId()));
        if (request.altUnitId().equals(material.getBaseUnitId())) {
            throw new BusinessException("conversion.base-unit",
                    "The base unit needs no conversion to itself.");
        }
        if (conversions.existsByMaterialIdAndAltUnitId(materialId, request.altUnitId())) {
            throw BusinessException.conflict("conversion.exists",
                    "A conversion for this unit already exists on the material.");
        }
        MaterialUnitConversion conversion = new MaterialUnitConversion(orgId(), materialId,
                request.altUnitId(), request.factorToBase());
        conversions.save(conversion);
        audit.record("MATERIAL", materialId, "CONVERSION_ADDED", null,
                Map.of("altUnitId", request.altUnitId().toString(),
                        "factorToBase", request.factorToBase()), null);
        return mapper.toResponse(conversion);
    }

    // ------------------------------------------------------------------ expense categories

    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> listExpenseCategories() {
        return expenseCategories.findByOrgIdOrderBySortOrderAscCodeAsc(orgId()).stream()
                .map(mapper::toResponse).toList();
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    public ExpenseCategoryResponse createExpenseCategory(SaveExpenseCategoryRequest request) {
        requireFreeCode(expenseCategories.existsByOrgIdAndCode(orgId(), request.code()),
                "Expense category", request.code());
        if (request.materialPurchase() && request.labourPayment()) {
            throw new BusinessException("expense-category.flags",
                    "A category cannot be both a material purchase and a labour payment.");
        }
        if (request.parentId() != null) {
            expenseCategories.findById(request.parentId())
                    .filter(c -> c.getOrgId().equals(orgId()))
                    .orElseThrow(() -> BusinessException.notFound("Expense category",
                            request.parentId()));
        }
        ExpenseCategory category = new ExpenseCategory(orgId(), request.code(), request.name(),
                request.parentId());
        category.setMaterialPurchase(request.materialPurchase());
        category.setLabourPayment(request.labourPayment());
        category.setRequiresVendor(request.requiresVendor());
        category.setSortOrder(request.sortOrder());
        expenseCategories.save(category);
        recordCreate("EXPENSE_CATEGORY", category.getId(), category.getCode());
        return mapper.toResponse(category);
    }

    // ------------------------------------------------------------------ internals

    private void applyMaterialFields(Material material, UUID categoryId, String hsnCode,
                                     java.math.BigDecimal gstPercent, java.math.BigDecimal minStock,
                                     java.math.BigDecimal standardRate, UUID preferredVendorId,
                                     boolean consumable) {
        material.setCategoryId(categoryId);
        material.setHsnCode(hsnCode);
        material.setGstPercent(gstPercent);
        material.setMinStockLevel(minStock == null ? java.math.BigDecimal.ZERO : minStock);
        material.setStandardRate(standardRate);
        material.setPreferredVendorId(preferredVendorId);
        material.setConsumable(consumable);
    }

    private Vendor requireVendor(UUID id) {
        return vendors.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Vendor", id));
    }

    private LabourContractor requireContractor(UUID id) {
        return contractors.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Labour contractor", id));
    }

    private Material requireMaterial(UUID id) {
        return materials.findByIdAndOrgIdAndDeletedAtIsNull(id, orgId())
                .orElseThrow(() -> BusinessException.notFound("Material", id));
    }

    private void recordCreate(String entityType, UUID id, String code) {
        audit.record(entityType, id, "CREATE", null, Map.of("code", code), null);
    }

    private UUID orgId() {
        return currentUser.currentOrgId();
    }

    private static void requireFreeCode(boolean exists, String what, String code) {
        if (exists) {
            throw BusinessException.conflict("masterdata.code-taken",
                    what + " code '" + code + "' is already in use.");
        }
    }

    private static void requireVersion(Long actual, Long presented, String what, UUID id) {
        if (!actual.equals(presented)) {
            throw new OptimisticLockingFailureException(what + " " + id + " was changed by someone else");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
