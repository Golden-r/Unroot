package org.uspace.config;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of reading {@code settings.properties}: the effective settings plus anything that had to be
 * ignored.
 *
 * <p>Invalid values fall back to the default and become an issue, so the user can see that
 * {@code confirm.beforeWriting=maybe} was not understood instead of silently getting an answer they
 * did not choose.
 *
 * @param settings effective values, always usable
 * @param issues   entries that were ignored or that forced a default
 */
public record SettingsLoad(Settings settings, List<MetadataIssue> issues) {

    public SettingsLoad {
        Objects.requireNonNull(settings, "settings");
        issues = List.copyOf(issues);
    }

    public static SettingsLoad defaults() {
        return new SettingsLoad(Settings.defaults(), List.of());
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
