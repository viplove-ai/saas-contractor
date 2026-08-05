package in.nirman;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Entry point for Nirman, a construction site management system.
 *
 * <p>This is a modular monolith. Each package under {@code modules} owns its tables and
 * exposes a service API. Cross-module access goes through those services, never through
 * another module's repositories.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class NirmanApplication {

    public static void main(String[] args) {
        SpringApplication.run(NirmanApplication.class, args);
    }
}
