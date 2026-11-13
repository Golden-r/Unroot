/**
 * Serialization and persisted preferences: one {@code meta.properties} per application under
 * {@code $XDG_DATA_HOME/userspace/apps/<id>/}, and {@code settings.properties} under
 * {@code $XDG_CONFIG_HOME/userspace/}.
 *
 * <p>One directory per application instead of a single registry file gives atomic per-app writes,
 * makes a single app inspectable and repairable by hand, and makes deletion honest. Every file
 * carries a {@code schema.version} key; unknown keys survive a rewrite so that a newer version's data
 * is not silently destroyed by an older one.
 *
 * <p>Two rules that are easy to get wrong and are therefore encoded once, in {@link
 * org.uspace.config.PropertiesFile}: UTF-8 {@code Reader}/{@code Writer} (never the byte-stream
 * overloads, which mangle non-Latin text), and write-temp-then-rename so a crash cannot truncate an
 * existing file.
 *
 * <p>Asymmetry worth knowing: application metadata fails loudly when it cannot be written, while
 * <em>loading</em> settings falls back to defaults and reports an issue — preferences are not data.
 *
 * <p>This package does not decide where files go; it receives resolved paths from {@code system}
 * (via {@code Main}), so the on-disk layout has exactly one definition.
 */
package org.uspace.config;
