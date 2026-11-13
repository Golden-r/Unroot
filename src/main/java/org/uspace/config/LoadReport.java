package org.uspace.config;

import java.util.List;
import org.uspace.core.model.Application;

/**
 * Outcome of reading every registration: what loaded, and what could not.
 *
 * <p>Returning both halves in one value makes it impossible for a caller to silently drop the
 * failures while displaying the successes.
 *
 * @param applications successfully decoded applications, ordered by id
 * @param issues       one entry per application directory that could not be loaded
 */
public record LoadReport(List<Application> applications, List<MetadataIssue> issues) {

    public LoadReport {
        applications = List.copyOf(applications);
        issues = List.copyOf(issues);
    }

    public static LoadReport empty() {
        return new LoadReport(List.of(), List.of());
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
