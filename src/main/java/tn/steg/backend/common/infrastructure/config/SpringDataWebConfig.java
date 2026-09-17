package tn.steg.backend.common.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Ensures paginated REST responses are serialized as stable {@code PagedModel}
 * DTOs rather than raw {@code PageImpl}, eliminating the
 * "Serializing PageImpl instances as-is is not supported" warning and
 * guaranteeing a version-stable JSON structure for clients.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class SpringDataWebConfig {
}
