package in.nirman.modules.identity.domain;

import in.nirman.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A login. Site supervisors, engineers, accountants and admins — never workers. */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "username", nullable = false, length = 60, updatable = false)
    private String username;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "mobile", length = 20)
    private String mobile;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "session_epoch", nullable = false)
    private long sessionEpoch;

    /**
     * The member's own signature, as a claimed attachment. Null until he uploads one. Drawn
     * onto the documents that carry his name — see V60 for why it hangs off the login and not
     * the staff record, and why nobody else may set it.
     */
    @Column(name = "signature_attachment_id")
    private UUID signatureAttachmentId;

    // Eager on purpose: the principal (roles → permissions) is assembled outside any
    // transaction on every login and refresh, and the sets are a handful of rows.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected User() {
    }

    public User(UUID orgId, String username, String fullName, String passwordHash) {
        this.orgId = orgId;
        this.username = username;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void changePassword(String newHash, boolean mustChange) {
        this.passwordHash = newHash;
        this.mustChangePassword = mustChange;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void registerFailedLogin(int maxAttempts, Instant lockUntilIfExceeded) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = lockUntilIfExceeded;
            this.failedLoginCount = 0;
        }
    }

    public void registerSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public long getSessionEpoch() {
        return sessionEpoch;
    }

    /**
     * Ends every session on this account, on every device, at once.
     *
     * <p>Revoking the refresh tokens is the other half and is done by the caller; this half
     * is what makes it immediate. An access token is a signed claim that no request looks
     * up, so without the counter a handset already holding one would keep working until it
     * expired — and a reset is asked for precisely when that is unacceptable.</p>
     *
     * <p>The caller's own device is signed out too, which is the point: the person asking
     * has no way to say which of the live sessions is the one they meant to keep. The
     * screens that need to carry on simply sign in again with the new password.</p>
     */
    public void endAllSessions() {
        this.sessionEpoch++;
    }

    public UUID getSignatureAttachmentId() {
        return signatureAttachmentId;
    }

    public void setSignatureAttachmentId(UUID signatureAttachmentId) {
        this.signatureAttachmentId = signatureAttachmentId;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void replaceRoles(Set<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }
}
