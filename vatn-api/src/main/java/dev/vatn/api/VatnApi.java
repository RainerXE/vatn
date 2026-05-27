package dev.vatn.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for all VATN API surfaces.
 * Used to track the machine-readable contract history and enforce version constraints.
 * 
 * Rules:
 * - patch = bug-fix only.
 * - minor = backward-compatible addition (new default methods).
 * - major = breaking change — requires ADR + one full major-version deprecation window.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface VatnApi {
    /**
     * The version in which this interface or method was introduced (e.g., "1.0").
     */
    String since();

    /**
     * If specified, the version in which this item was deprecated.
     */
    String deprecatedSince() default "";

    /**
     * If specified, the major version in which this item is scheduled for removal.
     */
    String removedIn() default "";
}
