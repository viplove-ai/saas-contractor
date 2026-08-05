package in.nirman.modules.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Worker trade: mason, helper, carpenter. isSkilled feeds wage analytics later. */
@Entity
@Table(name = "skill_categories")
public class SkillCategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_skilled", nullable = false)
    private boolean skilled = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected SkillCategory() {
    }

    public SkillCategory(UUID orgId, String code, String name, boolean skilled) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.skilled = skilled;
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

    public boolean isSkilled() {
        return skilled;
    }

    public void setSkilled(boolean skilled) {
        this.skilled = skilled;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
