package tn.steg.backend.common.domain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller endpoint as rate-limited.
 *
 * <p>Sits in the domain layer only so the marker is referenceable by both the
 * interface layer (annotating endpoints) and the infrastructure layer (the
 * enforcing aspect) without breaking the layer dependency rules. Enforcement
 * lives in {@code common.infrastructure.ratelimit.RateLimitingAspect}.
 *
 * <p>Each bucket is keyed by the endpoint's declared {@code name()} (or the
 * method signature when empty) plus the authenticated principal, so different
 * users never share quota and separate endpoints stay independent.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /**
     * Logical bucket name used for metrics and shared quota across methods
     * annotated with the same name. Empty means "per annotated method".
     */
    String name() default "";

    /** Maximum number of requests allowed within {@link #windowSeconds()}. */
    int limit() default 20;

    /** Sliding window length, in seconds. */
    int windowSeconds() default 60;
}