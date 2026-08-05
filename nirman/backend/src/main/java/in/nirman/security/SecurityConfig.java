package in.nirman.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                          @Value("${app.cors.allowed-methods}") String allowedMethods,
                          @Value("${app.security.bcrypt-strength:12}") int bcryptStrength) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.bcryptStrength = bcryptStrength;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           RestAuthenticationEntryPoint restEntryPoint) throws Exception {
        http
                // Stateless bearer-token API: no session to fix, no cookie to forge.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {})
                        .httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restEntryPoint)
                        .accessDeniedHandler(restEntryPoint))
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
