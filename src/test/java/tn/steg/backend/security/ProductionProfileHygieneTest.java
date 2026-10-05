package tn.steg.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 (production config) — the committed production profile itself is the
 * security boundary, so its TEXT is pinned here:
 *
 * <ul>
 *   <li><b>no localhost anywhere in {@code application-prod.yml}</b> — a
 *       production host must never fall back to a developer address;</li>
 *   <li><b>CORS from the environment only</b> — {@code ${CORS_ALLOWED_ORIGINS}}
 *       with NO default, so a forgotten env var fails the boot loudly instead
 *       of silently allowing the two dev origins;</li>
 *   <li><b>secure JWT settings</b> — the secret is required (no default) and
 *       both token lifetimes stay configured;</li>
 *   <li><b>the demo seeder can never run in production</b> — it is annotated
 *       {@code @Profile("integration")} and prod resources carry no demo
 *       credentials;</li>
 *   <li><b>calendar-day time zone default</b> — {@code APP_TIMEZONE:Africa/Tunis}
 *       in the base profile (ApplicationTimeZoneTest pins the behavior).</li>
 * </ul>
 *
 * <p>The behavioral prod-boot half (springdoc 404, actuator health-only,
 * real profile boot) lives in {@link ApiExposureProfileTest}.
 */
@DisplayName("Production profile hygiene — no dev addresses, env-only CORS, seeder unreachable")
class ProductionProfileHygieneTest {

    private static String resource(String path) {
        try (InputStream in = ProductionProfileHygieneTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    @Test
    @DisplayName("application-prod.yml contains no localhost address and no demo credentials")
    void prodProfileHasNoDevAddressesOrDemoData() {
        String prod = resource("/application-prod.yml");
        assertThat(prod).doesNotContain("localhost");
        assertThat(prod.toLowerCase()).doesNotContain("demo");
    }

    @Test
    @DisplayName("prod CORS comes strictly from the environment — no default, no dev origins")
    void prodCorsIsEnvOnlyWithoutDefault() {
        String prod = resource("/application-prod.yml");
        assertThat(prod).contains("allowed-origins: ${CORS_ALLOWED_ORIGINS}");
        // The base profile may keep the dev default; PROD must not.
        assertThat(prod).doesNotContain("CORS_ALLOWED_ORIGINS:http");
    }

    @Test
    @DisplayName("prod JWT secret is required (no default) and token lifetimes stay pinned")
    void prodJwtSettingsStaySecure() {
        String prod = resource("/application-prod.yml");
        assertThat(prod).contains("secret-key: ${STEG_JWT_SECRET}");
        assertThat(prod).doesNotContainPattern("STEG_JWT_SECRET:.");
        assertThat(prod).contains("access-token-expiration-minutes: ${JWT_ACCESS_EXP_MINUTES:15}");
        assertThat(prod).contains("refresh-token-expiration-days: ${JWT_REFRESH_EXP_DAYS:7}");
    }

    @Test
    @DisplayName("the demo seeder is annotated @Profile(\"integration\") — unreachable from prod")
    void demoSeederCannotRunInProduction() {
        Profile profile = tn.steg.backend.common.infrastructure.seed.IntegrationDemoSeeder.class
                .getAnnotation(Profile.class);
        assertThat(profile).as("IntegrationDemoSeeder must carry @Profile").isNotNull();
        assertThat(profile.value()).containsExactly("integration");
    }

    @Test
    @DisplayName("calendar-day time zone defaults to Africa/Tunis in the base profile")
    void timeZoneDefaultIsPinned() {
        String base = resource("/application.yml");
        assertThat(base).contains("timezone: ${APP_TIMEZONE:Africa/Tunis}");
    }
}
