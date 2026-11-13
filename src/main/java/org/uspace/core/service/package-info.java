/**
 * Use-case facade: one method per thing the user can ask for ({@code register}, {@code scan},
 * {@code integrate}, {@code remove}, {@code repair}, {@code launch}).
 *
 * <p>This is the only package the UI calls. It orchestrates {@code core.registry},
 * {@code discovery} and {@code integration} in the correct order, does the IO off the event
 * dispatch thread, and returns small result objects the UI can display. Progress and cancellation
 * are expressed with plain callbacks or {@code SwingWorker}, not with an event bus.
 */
package org.uspace.core.service;
