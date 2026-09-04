package tn.steg.backend.common.interfaces.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST controller endpoint as intentionally public (unauthenticated).
 * Controller methods without this annotation MUST be protected with security annotations
 * such as {@code @PreAuthorize} to satisfy the deny-by-default architectural rule.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}
