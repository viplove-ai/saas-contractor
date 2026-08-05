package in.nirman.modules.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "material_categories")
public class MaterialCategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected MaterialCategory() {
    }

    public MaterialCategory(UUID orgId, String code, String name, UUID parentId) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.parentId = parentId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
