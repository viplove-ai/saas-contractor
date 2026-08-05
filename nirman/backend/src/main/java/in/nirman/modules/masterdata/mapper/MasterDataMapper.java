package in.nirman.modules.masterdata.mapper;

import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ConversionResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ExpenseCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.LabourContractorResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SkillCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UnitResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.VendorResponse;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.domain.LabourContractor;
import in.nirman.modules.masterdata.domain.Material;
import in.nirman.modules.masterdata.domain.MaterialCategory;
import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.domain.Vendor;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MasterDataMapper {

    UnitResponse toResponse(Unit unit);

    SkillCategoryResponse toResponse(SkillCategory skillCategory);

    MaterialCategoryResponse toResponse(MaterialCategory materialCategory);

    VendorResponse toResponse(Vendor vendor);

    LabourContractorResponse toResponse(LabourContractor labourContractor);

    MaterialResponse toResponse(Material material);

    ConversionResponse toResponse(MaterialUnitConversion conversion);

    ExpenseCategoryResponse toResponse(ExpenseCategory expenseCategory);
}
