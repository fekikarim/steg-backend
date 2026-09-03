package tn.steg.backend.common.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 3 configuration exposing comprehensive interactive API documentation.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI stegOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("STEG Smart Internship & Administrative Platform API")
                        .description("REST API specifications for STEG intern lifecycle, administrative workflows, and finance validation.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("STEG Engineering Team")
                                .email("contact@steg.com.tn")
                                .url("https://www.steg.com.tn"))
                        .license(new License()
                                .name("Proprietary - STEG")
                                .url("https://www.steg.com.tn/license")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Provide your Bearer token in the format: Bearer <JWT>")));
    }
}
