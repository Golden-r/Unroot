/**
 * Turning a registered application into something the desktop and the shell can use, reversibly.
 *
 * <p>The one deliberate pattern in the codebase lives here: {@code IntegrationPlan} (an ordered,
 * immutable list of file operations), {@code IntegrationPlanner} (builds it without touching disk,
 * so the UI can say "this will create 4 files" before the user consents) and
 * {@code IntegrationApplier} (journals pre-images, applies, verifies, and can roll back).
 *
 * <p>Reversibility is the reason for the split. A planner that writes while it decides cannot offer
 * undo, and an applier without a plan cannot report what it is about to overwrite.
 *
 * <p>Channel-specific file formats live in {@code integration.desktop} and {@code integration.shell};
 * this package decides order and policy, not escaping.
 */
package org.uspace.integration;
