package in.nirman.modules.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A unit of measure. decimalPlaces drives quantity rounding and entry validation. */
@Entity
@Table(name = "units")
public class Unit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "decimal_places", nullable = false)
    private int decimalPlaces = 3;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Unit() {
    }

    public Unit(UUID orgId, String code, String name, int decimalPlaces) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.decimalPlaces = decimalPlaces;
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

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public void setDecimalPlaces(int decimalPlaces) {
        this.decimalPlaces = decimalPlaces;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
