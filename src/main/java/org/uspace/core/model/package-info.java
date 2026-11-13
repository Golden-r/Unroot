/**
 * Immutable domain types: what an application <em>is</em>.
 *
 * <p>Rules for this package: no IO, no Swing, no dependency on any other UserSpace package, and
 * validation only of invariants that do not require touching the filesystem. That keeps the model
 * cheap to construct in the UI thread, trivial to serialize, and testable without fixtures.
 */
package org.uspace.core.model;
