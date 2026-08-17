package in.nirman.modules.masterdata.service;

import in.nirman.common.BusinessException;
import in.nirman.common.DocumentNumberService;
import in.nirman.common.DocumentNumberService.DocType;
import in.nirman.common.PageResponse;
import in.nirman.modules.audit.AuditService;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.AddFieldMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ConversionResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CorrectFieldMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CreateMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.CreateVendorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ExpenseCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.NameExpenseCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveConversionRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveExpenseCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveMaterialCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveSkillCategoryRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SaveUnitRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SkillCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UnitResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UpdateMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UpdateVendorRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.VendorResponse;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.domain.Material;
import in.nirman.modules.masterdata.domain.MaterialCategory;
import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.mapper.MasterDataMapper;
import in.nirman.modules.masterdata.repository.ExpenseCategoryRepository;
import in.nirman.modules.masterdata.repository.MaterialCategoryRepository;
import in.nirman.modules.masterdata.repository.MaterialRepository;
import in.nirman.modules.masterdata.repository.MaterialUnitConversionRepository;
import in.nirman.modules.masterdata.repository.SkillCategoryRepository;
import in.nirman.modules.masterdata.repository.UnitRepository;
import in.nirman.modules.masterdata.repository.VendorRepository;
import in.nirman.security.CurrentUserProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Where a head named at a site sorts: after everything the office set up. */
    private static final int FIELD_NAMED_SORT_ORDER = 900;

    private final UnitRepository units;
    private final SkillCategoryRepository skillCategories;
    private final MaterialCategoryRepository materialCategories;
    private final VendorRepository vendors;
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

    // vendor:write, not masterdata:write (V21). Onboarding a dealer is the accountant's
    // work — he holds the GSTIN, the bank details and the credit terms — and he deliberately
    // does not hold masterdata:write, where a mistake reaches every screen in the system.
    @PreAuthorize("hasAuthority('vendor:write')")
    public VendorResponse createVendor(CreateVendorRequest request) {
        String code = emptyToNull(request.code());
        if (code == null) {
            code = generateVendorCode(request.vendorType(), request.name());
        } else {
            requireFreeCode(vendors.existsByOrgIdAndCode(orgId(), code), "Vendor", code);
        }
        Vendor vendor = new Vendor(orgId(), code, request.name(), request.vendorType());
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

    @PreAuthorize("hasAuthority('vendor:write')")
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

    // ------------------------------------------------------------------ materials

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> listMaterials(UUID categoryId, Boolean active, String q,
                                                        Pageable pageable) {
        Page<Material> page = materials.search(orgId(), categoryId, active, emptyToNull(q),
                pageable);
        // One query for every row's conversions rather than one per row. The picker on the
        // receive screen needs them on every material it offers, not on the one chosen.
        Map<UUID, List<UUID>> altUnits = altUnitsFor(page.getContent().stream()
                .map(Material::getId).toList());
        return PageResponse.from(page,
                material -> mapper.toResponse(material,
                        altUnits.getOrDefault(material.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public MaterialResponse getMaterial(UUID id) {
        return toMaterialResponse(requireMaterial(id));
    }

    /**
     * The units a material may be booked in besides its base one.
     *
     * <p>On the response because the alternative is a picker offering every unit in the
     * system: {@code MaterialLookup.factorToBase} refuses anything with no conversion, and
     * it refuses it after the storekeeper has typed the whole delivery.</p>
     */
    private Map<UUID, List<UUID>> altUnitsFor(List<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        return conversions.findByMaterialIdIn(materialIds).stream()
                .collect(Collectors.groupingBy(MaterialUnitConversion::getMaterialId,
                        Collectors.mapping(MaterialUnitConversion::getAltUnitId,
                                Collectors.toList())));
    }

    private MaterialResponse toMaterialResponse(Material material) {
        return mapper.toResponse(material, conversions.findByMaterialId(material.getId()).stream()
                .map(MaterialUnitConversion::getAltUnitId).toList());
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
        return toMaterialResponse(material);
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
            return toMaterialResponse(existing.get(0));
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
        return toMaterialResponse(material);
    }

    /**
     * The name corrected by whoever gave it, and the row put back in front of the office.
     *
     * <p>{@link #addFieldMaterial} let the field name a thing so the lorry at the gate was not
     * sent away. It left the field unable to say it had named it wrong — "celment" on the
     * picker for a year, or worse, a second row typed to get around the first, which is exactly
     * the split balance the naming rule exists to prevent. So the same permission corrects the
     * same field: the name, and the name only.</p>
     *
     * <p><b>The correction is not quiet.</b> It marks the row {@code provisional} again, the
     * way {@code SiteEquipment.reopen()} sends a corrected machine back to the queue: the
     * office's vetting was of a name that has since changed, so it has to be done again. An
     * office caller — anybody holding {@code masterdata:write} — does not re-open it, for the
     * same reason its own entry needs no approval.</p>
     *
     * <p>Nothing that carries a number is reachable from here, and neither is the unit or the
     * active flag. That is the invariant this endpoint's twin was built on: the field may name
     * a thing and never value it.</p>
     */
    @PreAuthorize("hasAnyAuthority('masterdata:provisional', 'masterdata:write')")
    public MaterialResponse correctFieldMaterial(UUID id, CorrectFieldMaterialRequest request) {
        Material material = requireMaterial(id);
        requireVersion(material.getVersion(), request.version(), "Material", id);

        String name = request.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BusinessException("material.name-required",
                    "A material needs a name to be booked against.");
        }
        /*
          The duplicate check addFieldMaterial makes on the way in, made again on the way
          through: renaming cement to "OPC 43" when the catalogue already holds an "OPC 43"
          produces the two-rows-one-material state by the back door, and the stock in the shed
          would answer to neither balance.
        */
        boolean taken = materials.findByName(orgId(), name).stream()
                .anyMatch(other -> !other.getId().equals(id));
        if (taken) {
            throw BusinessException.conflict("material.name-taken",
                    "The catalogue already holds a material called " + name
                            + ". Book against that one — two rows for it would split its "
                            + "stock into two balances, and neither would be what is in the "
                            + "shed.");
        }

        String was = material.getName();
        material.setName(name);
        if (!currentUser.hasPermission("masterdata:write")) {
            material.setProvisional(true);
        }
        audit.record("MATERIAL", material.getId(), "FIELD_RENAME", null,
                Map.of("name", name, "was", was, "provisional", material.isProvisional()), null);
        return toMaterialResponse(material);
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
        return toMaterialResponse(material);
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
        // What the approver will be shown already chosen. Absent means the site's, which is
        // what all but the office heads are.
        category.setDefaultAllocation(request.defaultAllocation());
        category.setSortOrder(request.sortOrder());
        expenseCategories.save(category);
        recordCreate("EXPENSE_CATEGORY", category.getId(), category.getCode());
        return mapper.toResponse(category);
    }

    /**
     * An expense head named at a site, because the money has already been spent.
     *
     * <p>An expense is booked against a head and cannot be booked without one, and the
     * starting taxonomy is the CPWD list every organisation gets. The first bill for
     * something that list never imagined leaves a supervisor picking whichever head is least
     * wrong — which is how a month's "Miscellaneous" becomes a figure the office cannot break
     * down. This is the alternative: he says what it was, and it becomes a head.</p>
     *
     * <p>What he may set is the name and nothing else. The two flags decide whether the
     * head's rows are cost at all — material purchase is inventory, labour payment settles a
     * wage attendance has already counted — and either one guessed from a site double-counts
     * a month. So both stay false, which is the ordinary case, and the row is marked
     * {@code provisional} so the office knows nobody decided otherwise.</p>
     *
     * <p>A name the organisation already holds comes back as the head it already holds, on
     * the reasoning behind {@link #addFieldMaterial}: two heads called "site cleaning" split
     * one figure across two lines of the same report.</p>
     */
    @PreAuthorize("hasAuthority('masterdata:provisional:head')")
    public ExpenseCategoryResponse nameExpenseCategory(NameExpenseCategoryRequest request) {
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BusinessException("expense-category.name-required",
                    "An expense needs to say what kind of spending it was.");
        }
        List<ExpenseCategory> existing = expenseCategories.findByName(orgId(), name);
        if (!existing.isEmpty()) {
            return mapper.toResponse(existing.get(0));
        }

        ExpenseCategory category = new ExpenseCategory(orgId(),
                documentNumbers.next(orgId(), DocType.EXPENSE_CATEGORY, LocalDate.now()),
                name, null);
        category.setProvisional(true);
        // Under the heads the office set up, in every picker that sorts by this. A head
        // nobody vetted is not the first answer anybody should reach for.
        category.setSortOrder(FIELD_NAMED_SORT_ORDER);
        expenseCategories.save(category);
        recordCreate("EXPENSE_CATEGORY", category.getId(), category.getCode());
        return mapper.toResponse(category);
    }

    // ------------------------------------------------------------------ internals

    /**
     * A short code for a supplier nobody was ever really choosing one for.
     *
     * <p>The field was asked for and answered badly: one person typed the firm's initials,
     * the next typed the town it delivers from, and the register ended up with codes that
     * sort into no order and tell you nothing. It is derived instead, from the two things
     * that identify a supplier at a glance — what he supplies and what he is called —
     * {@code MAT-SHIVSHAKTI}, {@code TRN-KUMAON}.</p>
     *
     * <p>Uniqueness is the whole reason the server does it and not the screen. A clash gets a
     * counter, and a name with nothing usable in it (a firm written entirely in Devanagari,
     * say) falls back to the same document-number counter that gives materials and workers
     * theirs — a code nobody would choose, but never a collision and never a refusal while
     * somebody is standing at a gate.</p>
     */
    private String generateVendorCode(Vendor.Type type, String name) {
        String base = VENDOR_CODE_PREFIX.getOrDefault(type, "SUP") + "-" + slug(name);
        if (!vendors.existsByOrgIdAndCode(orgId(), base)) {
            return base;
        }
        // Two firms of the same name supplying the same thing is rare and real — a dealer and
        // his brother's yard. The second one is -2.
        for (int suffix = 2; suffix <= 9; suffix++) {
            String candidate = base + "-" + suffix;
            if (!vendors.existsByOrgIdAndCode(orgId(), candidate)) {
                return candidate;
            }
        }
        return documentNumbers.next(orgId(), DocType.VENDOR, LocalDate.now());
    }

    /** What he supplies, in three letters, at the front of his code. */
    private static final Map<Vendor.Type, String> VENDOR_CODE_PREFIX = Map.of(
            Vendor.Type.MATERIAL, "MAT",
            Vendor.Type.SUBCONTRACTOR, "SUB",
            Vendor.Type.SERVICE, "SRV",
            Vendor.Type.TRANSPORT, "TRN",
            Vendor.Type.OTHER, "GEN");

    /**
     * The firm's name as a code fragment: capitals and digits only, whole words, stopping at
     * {@value #VENDOR_SLUG_MAX} characters. Whole words because {@code SHIVSHAKTI} is
     * recognisable and {@code SHIVSHAK} is a typo somebody will try to correct.
     */
    private static String slug(String name) {
        StringBuilder slug = new StringBuilder();
        for (String word : name.toUpperCase(java.util.Locale.ROOT).split("[^A-Z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (slug.length() + word.length() > VENDOR_SLUG_MAX && slug.length() > 0) {
                break;
            }
            slug.append(word.length() > VENDOR_SLUG_MAX
                    ? word.substring(0, VENDOR_SLUG_MAX) : word);
            if (slug.length() >= VENDOR_SLUG_MAX) {
                break;
            }
        }
        return slug.length() == 0 ? "SUPPLIER" : slug.toString();
    }

    private static final int VENDOR_SLUG_MAX = 14;

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
