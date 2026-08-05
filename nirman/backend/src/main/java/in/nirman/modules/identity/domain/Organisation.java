package in.nirman.modules.identity.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The contractor firm. v1 runs single-org, but every business row already carries org_id. */
@Entity
@Table(name = "organisations")
public class Organisation extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "address")
    private String address;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", columnDefinition = "char(3)", nullable = false)
    private String currencyCode = "INR";

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Kolkata";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Organisation() {
    }

    public Organisation(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getGstin() {
        return gstin;
    }

    public String getPan() {
        return pan;
    }

    public String getAddress() {
        return address;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isActive() {
        return active;
    }
}
