package in.nirman.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token settings. The secret must be at least 32 bytes because the tokens are signed with
 * HMAC-SHA256; a shorter value fails fast at startup instead of weakening every token.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @Min(1) long accessTokenMinutes,
        @Min(1) long refreshTokenDays,
        @NotBlank String issuer) {

    public JwtProperties {
        if (secret != null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
    }
}
