package in.nirman.modules.project.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** A stock location inside a site. Every stock movement names a store, never a bare site. */
@Entity
@Table(name = "stores")
public class Store extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "site_id", nullable = false, updatable = false)
    private UUID siteId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "is_default", nullable = false)
    private boolean defaultStore = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Store() {
    }

    public Store(UUID orgId, UUID siteId, String code, String name) {
        this.orgId = orgId;
        this.siteId = siteId;
        this.code = code;
        this.name = name;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSiteId() {
        return siteId;
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

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isDefaultStore() {
        return defaultStore;
    }

    public void setDefaultStore(boolean defaultStore) {
        this.defaultStore = defaultStore;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
