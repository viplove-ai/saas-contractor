package in.nirman.modules.masterdata.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A supplier of material, transport or services. Balances build on openingBalance in Phase 5. */
@Entity
@Table(name = "vendors")
public class Vendor extends BaseEntity {

    public enum Type { MATERIAL, SUBCONTRACTOR, SERVICE, TRANSPORT, OTHER }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor_type", nullable = false, length = 30)
    private Type vendorType = Type.MATERIAL;

    /**
     * What he supplies, written out, when the list has no word for it — the scaffolding hire,
     * the surveyor, the man with the water tanker.
     *
     * <p>Only ever set alongside {@link Type#OTHER}, and a check constraint keeps it that way:
     * "Material dealer" with "supplies scaffolding" written beside it is two answers to one
     * question, and the second is invisible on every screen that shows the first. See V53.</p>
     */
    @Column(name = "supplies_note", length = 120)
    private String suppliesNote;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "mobile", length = 20)
    private String mobile;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "address")
    private String address;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "pan", length = 10)
    private String pan;

    @Column(name = "bank_account_no", length = 30)
    private String bankAccountNo;

    @Column(name = "bank_ifsc", length = 15)
    private String bankIfsc;

    @Column(name = "credit_days", nullable = false)
    private int creditDays;

    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Named from the field with contact details only — no tax numbers, no bank account, no
     * credit terms — and nobody in the office has looked at him yet. See V50: it is the same
     * flag {@code materials.provisional} carries, for the same reason.
     */
    @Column(name = "provisional", nullable = false)
    private boolean provisional;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Vendor() {
    }

    public Vendor(UUID orgId, String code, String name, Type vendorType) {
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.vendorType = vendorType;
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

    public Type getVendorType() {
        return vendorType;
    }

    public void setVendorType(Type vendorType) {
        this.vendorType = vendorType;
    }

    public String getSuppliesNote() {
        return suppliesNote;
    }

    public void setSuppliesNote(String suppliesNote) {
        this.suppliesNote = suppliesNote;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getPan() {
        return pan;
    }

    public void setPan(String pan) {
        this.pan = pan;
    }

    public String getBankAccountNo() {
        return bankAccountNo;
    }

    public void setBankAccountNo(String bankAccountNo) {
        this.bankAccountNo = bankAccountNo;
    }

    public String getBankIfsc() {
        return bankIfsc;
    }

    public void setBankIfsc(String bankIfsc) {
        this.bankIfsc = bankIfsc;
    }

    public int getCreditDays() {
        return creditDays;
    }

    public void setCreditDays(int creditDays) {
        this.creditDays = creditDays;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public void setProvisional(boolean provisional) {
        this.provisional = provisional;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
