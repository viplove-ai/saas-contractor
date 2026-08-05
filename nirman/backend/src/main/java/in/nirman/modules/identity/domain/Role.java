package in.nirman.modules.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Set;
import java.util.UUID;

/**
 * A named bundle of permissions. The four system roles ({@code org_id IS NULL},
 * {@code is_system}) are created by migration V2 and shared by every organisation;
 * org-specific roles are a later feature the schema already allows.
 *
 * <p>Does not extend {@link in.nirman.common.BaseEntity}: the table carries no
 * created_by/updated_by columns, deliberately — roles change by migration, not by users.</p>
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Eager on purpose: permission codes are needed whenever a role is loaded (claims
    // building), and the whole catalogue is under a hundred rows.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions;

    protected Role() {
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

    public String getDescription() {
        return description;
    }

    public boolean isSystem() {
        return system;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Role that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
