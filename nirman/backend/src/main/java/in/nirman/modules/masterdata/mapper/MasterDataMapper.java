package in.nirman.modules.masterdata.mapper;

import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ConversionResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.ExpenseCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.MaterialResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.SkillCategoryResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.UnitResponse;
import in.nirman.modules.masterdata.api.dto.MasterDataDtos.VendorResponse;
import in.nirman.modules.masterdata.domain.ExpenseCategory;
import in.nirman.modules.masterdata.domain.Material;
import in.nirman.modules.masterdata.domain.MaterialCategory;
import in.nirman.modules.masterdata.domain.MaterialUnitConversion;
import in.nirman.modules.masterdata.domain.SkillCategory;
import in.nirman.modules.masterdata.domain.Unit;
import in.nirman.modules.masterdata.domain.Vendor;
import org.mapstruct.Mapper;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface MasterDataMapper {

    UnitResponse toResponse(Unit unit);

    SkillCategoryResponse toResponse(SkillCategory skillCategory);

    MaterialCategoryResponse toResponse(MaterialCategory materialCategory);

    VendorResponse toResponse(Vendor vendor);

    /**
     * The material plus the units it can be booked in. A parameter rather than something off
     * the entity, because the conversions live in their own table and a register of two
     * hundred materials loads all of them in one query.
     */
    MaterialResponse toResponse(Material material, List<UUID> altUnitIds);

    ConversionResponse toResponse(MaterialUnitConversion conversion);

    ExpenseCategoryResponse toResponse(ExpenseCategory expenseCategory);
}
