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

    /**
     * Unique inside the organisation. Not frozen: a store created with its site is named by
     * the machine ({@code site-<site code>}), and an organisation that keeps three lockups
     * per site has to be able to say which is which.
     */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 210)
    private String name;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "is_default", nullable = false)
    private boolean defaultStore = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * What a store created alongside its site is called, before anybody renames it.
     *
     * <p>Derived rather than asked for. A site has somewhere to put the cement whether or not
     * the person adding it thought about stores, and an empty store picker on the receive
     * screen is a supervisor stuck at the gate with a lorry.</p>
     */
    public static final String SITE_STORE_PREFIX = "site-";

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

    public void setCode(String code) {
        this.code = code;
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
