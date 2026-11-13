/**
 * Swing presentation layer: windows, table models, dialogs, and the small controller that forwards
 * user intents to {@code core.service}.
 *
 * <p>The rule that keeps the GUI replaceable: this package imports no {@code java.nio.file} and no
 * {@code ProcessBuilder}. Everything shown is derived from {@code core.model} values handed to it,
 * and every action is a method call on the service facade. All filesystem and process work happens
 * off the event dispatch thread.
 */
package org.uspace.ui;
