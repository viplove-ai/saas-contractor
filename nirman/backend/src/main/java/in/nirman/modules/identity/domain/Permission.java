package in.nirman.modules.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One grantable capability, e.g. {@code attendance:verify}. Rows come from migrations
 * only — the API can read the catalogue but never writes it.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 60, unique = true)
    private String code;

    @Column(name = "module", nullable = false, length = 40)
    private String module;

    @Column(name = "description")
    private String description;

    protected Permission() {
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getModule() {
        return module;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Permission that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
