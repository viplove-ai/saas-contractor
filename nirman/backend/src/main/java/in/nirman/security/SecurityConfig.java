package in.nirman.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless bearer-token security. {@link JwtAuthenticationFilter} authenticates each
 * request from its access token; everything under /api except login and refresh requires
 * one. Method-level rules ({@code @PreAuthorize} on services) carry the permission matrix,
 * and {@link SiteAccessGuard} carries the site scope — this class only decides
 * authenticated versus anonymous.
 */
@Configuration
@EnableMethodSecurity          // enables @PreAuthorize on service methods
public class SecurityConfig {

    private final String allowedOrigins;
    private final String allowedMethods;
    private final int bcryptStrength;

    /**
     * Whether Swagger and the OpenAPI document may be read without signing in.
     *
     * <p>They are not a vulnerability in themselves — every endpoint behind them still
     * demands a token and a permission. They are a map: every route, every field name, every
     * enum value, and which of them are reachable anonymously. Nobody on a production
     * deployment of this needs that map who is not already holding the source, so the prod
     * profile sets this false and the paths fall through to {@code authenticated()}.</p>
     */
    private final boolean apiDocsPublic;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                          @Value("${app.cors.allowed-methods}") String allowedMethods,
                          @Value("${app.security.bcrypt-strength:12}") int bcryptStrength,
                          @Value("${app.api-docs.public:true}") boolean apiDocsPublic) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.bcryptStrength = bcryptStrength;
        this.apiDocsPublic = apiDocsPublic;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           LoginRateLimitFilter loginRateLimitFilter,
                                           RestAuthenticationEntryPoint restEntryPoint) throws Exception {
        http
                // Stateless bearer-token API: no session to fix, no cookie to forge.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {})
                        .httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31536000))
                        /*
                          The API answers JSON and nothing else, so the policy that fits it is
                          the one that permits nothing: no script may run, no frame may embed
                          it, no image may load. It costs nothing here and it is the
                          difference between a reflected value in an error body being inert
                          and being a script, on the day somebody opens a /api/v1 URL in a
                          browser tab — which is how half of these are found.
                        */
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; sandbox"))
                        // A signed download URL for a bill is a bearer credential in a query
                        // string. Sending no referrer at all is what stops it travelling to
                        // whatever the next page happens to be.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicy.NO_REFERRER))
                        // Last in the chain: this one returns its own config object rather
                        // than the headers configurer, so nothing can follow it.
                        .permissionsPolicy(policy -> policy.policy(
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/info").permitAll();
                    if (apiDocsPublic) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restEntryPoint)
                        .accessDeniedHandler(restEntryPoint))
                // Ahead of authentication: the point of the limit is to be cheap, and running
                // it after a BCrypt-12 verification would mean paying for the attack in order
                // to refuse it.
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id",
                "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
