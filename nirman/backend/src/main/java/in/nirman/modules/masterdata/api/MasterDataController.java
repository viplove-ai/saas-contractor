package in.nirman.modules.masterdata.api;

import in.nirman.common.PageResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.AddFieldMaterialRequest;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ConversionResponse;
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
import in.nirman.modules.masterdata.domain.Vendor;
import in.nirman.modules.masterdata.service.MasterDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Master data: units, categories, vendors, suppliers, materials. One controller because
 * the endpoints are uniform CRUD over reference rows; paths follow docs/05.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Master data", description = "Units, materials, vendors, contractors and categories")
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ units

    @GetMapping("/units")
    public List<UnitResponse> listUnits() {
        return service.listUnits();
    }

    @PostMapping("/units")
    public ResponseEntity<UnitResponse> createUnit(@Valid @RequestBody SaveUnitRequest request) {
        UnitResponse created = service.createUnit(request);
        return ResponseEntity.created(URI.create("/api/v1/units/" + created.id())).body(created);
    }

    // ------------------------------------------------------------------ skill categories

    @GetMapping("/skill-categories")
    public List<SkillCategoryResponse> listSkillCategories() {
        return service.listSkillCategories();
    }

    @PostMapping("/skill-categories")
    public ResponseEntity<SkillCategoryResponse> createSkillCategory(
            @Valid @RequestBody SaveSkillCategoryRequest request) {
        SkillCategoryResponse created = service.createSkillCategory(request);
        return ResponseEntity.created(
                URI.create("/api/v1/skill-categories/" + created.id())).body(created);
    }

    // ------------------------------------------------------------------ material categories

    @GetMapping("/material-categories")
    public List<MaterialCategoryResponse> listMaterialCategories() {
        return service.listMaterialCategories();
    }

    @PostMapping("/material-categories")
    public ResponseEntity<MaterialCategoryResponse> createMaterialCategory(
            @Valid @RequestBody SaveMaterialCategoryRequest request) {
        MaterialCategoryResponse created = service.createMaterialCategory(request);
        return ResponseEntity.created(
                URI.create("/api/v1/material-categories/" + created.id())).body(created);
    }

    // ------------------------------------------------------------------ vendors

    @GetMapping("/vendors")
    public PageResponse<VendorResponse> listVendors(
            @RequestParam(required = false) Vendor.Type type,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.listVendors(type, active, q,
                PageRequest.of(page, Math.min(size, 100), Sort.by("code")));
    }

    @PostMapping("/vendors")
    public ResponseEntity<VendorResponse> createVendor(
            @Valid @RequestBody CreateVendorRequest request) {
        VendorResponse created = service.createVendor(request);
        return ResponseEntity.created(URI.create("/api/v1/vendors/" + created.id())).body(created);
    }

    @GetMapping("/vendors/{id}")
    public VendorResponse getVendor(@PathVariable UUID id) {
        return service.getVendor(id);
    }

    @PutMapping("/vendors/{id}")
    public VendorResponse updateVendor(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateVendorRequest request) {
        return service.updateVendor(id, request);
    }

    // ------------------------------------------------------------------ labour suppliers
    //
    // There are no endpoints here any more. A man who brings a gang is a supplier like the
    // one who brings cement, and V23 folded labour_contractors into vendors: the list is
    // GET /vendors?type=SUBCONTRACTOR, and it comes with the account, the advances and the
    // history that the separate register never had.

    // ------------------------------------------------------------------ materials

    @GetMapping("/materials")
    public PageResponse<MaterialResponse> listMaterials(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.listMaterials(categoryId, active, q,
                PageRequest.of(page, Math.min(size, 100), Sort.by("code")));
    }

    @PostMapping("/materials")
    public ResponseEntity<MaterialResponse> createMaterial(
            @Valid @RequestBody CreateMaterialRequest request) {
        MaterialResponse created = service.createMaterial(request);
        return ResponseEntity.created(URI.create("/api/v1/materials/" + created.id())).body(created);
    }

    @PostMapping("/materials/field")
    @Operation(summary = "Name a material at the gate while booking a delivery",
            description = "For the storekeeper, not the office: a name and a unit, nothing else. "
                    + "The row is marked provisional. A material already carrying the same name "
                    + "is returned rather than duplicated, because two rows would split its stock "
                    + "into two balances.")
    public ResponseEntity<MaterialResponse> addFieldMaterial(
            @Valid @RequestBody AddFieldMaterialRequest request) {
        MaterialResponse created = service.addFieldMaterial(request);
        return ResponseEntity.created(URI.create("/api/v1/materials/" + created.id())).body(created);
    }

    @GetMapping("/materials/{id}")
    public MaterialResponse getMaterial(@PathVariable UUID id) {
        return service.getMaterial(id);
    }

    @PutMapping("/materials/{id}")
    public MaterialResponse updateMaterial(@PathVariable UUID id,
                                           @Valid @RequestBody UpdateMaterialRequest request) {
        return service.updateMaterial(id, request);
    }

    @GetMapping("/materials/{id}/conversions")
    public List<ConversionResponse> listConversions(@PathVariable UUID id) {
        return service.listConversions(id);
    }

    @PostMapping("/materials/{id}/conversions")
    @Operation(summary = "Add an alternative unit; factorToBase converts one alt unit into base units")
    public ResponseEntity<ConversionResponse> addConversion(
            @PathVariable UUID id, @Valid @RequestBody SaveConversionRequest request) {
        ConversionResponse created = service.addConversion(id, request);
        return ResponseEntity.created(
                URI.create("/api/v1/materials/" + id + "/conversions/" + created.id())).body(created);
    }

    // ------------------------------------------------------------------ expense categories

    @GetMapping("/expense-categories")
    public List<ExpenseCategoryResponse> listExpenseCategories() {
        return service.listExpenseCategories();
    }

    @PostMapping("/expense-categories")
    public ResponseEntity<ExpenseCategoryResponse> createExpenseCategory(
            @Valid @RequestBody SaveExpenseCategoryRequest request) {
        ExpenseCategoryResponse created = service.createExpenseCategory(request);
        return ResponseEntity.created(
                URI.create("/api/v1/expense-categories/" + created.id())).body(created);
    }

    @PostMapping("/expense-categories/field")
    @Operation(summary = "Name an expense head at the site while booking an expense",
            description = "For whoever is holding the bill, not the office: a name and "
                    + "nothing else. The row is marked provisional and neither cost flag is "
                    + "set. A head already carrying the same name is returned rather than "
                    + "duplicated, because two heads for one kind of spending split a "
                    + "month's figure across two lines of the same report.")
    public ResponseEntity<ExpenseCategoryResponse> nameExpenseCategory(
            @Valid @RequestBody NameExpenseCategoryRequest request) {
        ExpenseCategoryResponse created = service.nameExpenseCategory(request);
        return ResponseEntity.created(
                URI.create("/api/v1/expense-categories/" + created.id())).body(created);
    }
}
