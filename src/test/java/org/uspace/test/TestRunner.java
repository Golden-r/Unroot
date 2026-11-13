package org.uspace.test;

import java.util.ArrayList;
import java.util.List;
import org.uspace.test.TestSupport.Skip;

/**
 * Minimal zero-dependency test runner.
 *
 * <p>Chosen over JUnit deliberately: the project has no build tool and no dependency management, so
 * a downloaded framework would make {@code ./build.sh test} fail on a machine without network
 * access. Correctness of the persistence layer is what this milestone needs, and a named case that
 * throws and is reported is enough for that. Swapping in JUnit 5 later means re-expressing the same
 * assertions, not rewriting the tests' logic.
 */
public final class TestRunner {

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    private int passed;
    private int skipped;
    private final List<String> failures = new ArrayList<>();

    /** Runs one named case; a thrown exception fails it and execution continues. */
    public void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Skip e) {
            skipped++;
            System.out.println("  SKIP  " + name + "  (" + e.getMessage() + ")");
        } catch (Throwable t) {
            failures.add(name + " — " + t);
            System.out.println("  FAIL  " + name);
            System.out.println("        " + t);
            StackTraceElement[] trace = t.getStackTrace();
            if (trace.length > 0) {
                System.out.println("        at " + trace[0]);
            }
        }
    }

    public void section(String title) {
        System.out.println("\n" + title);
    }

    /** Prints the summary and returns the process exit code. */
    public int report() {
        int total = passed + skipped + failures.size();
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("OK — " + passed + " passed, " + skipped + " skipped, " + total + " total");
            return 0;
        }
        System.out.println("FAILED — " + failures.size() + " of " + total + " failed");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        return 1;
    }
}
