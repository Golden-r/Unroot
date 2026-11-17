package org.uspace.test;

import org.uspace.config.FileSafetyTests;
import org.uspace.test.DiscoveryTests;

/**
 * Entry point for {@code ./build.sh test}.
 *
 * <p>Exit code 0 means every case passed; 1 means at least one failed. Cases that cannot run in the
 * current environment (permission tests as root) are reported as skipped, never as passed.
 */
public final class TestMain {

    private TestMain() {
    }

    public static void main(String[] args) {
        System.out.println("UserSpace persistence, settings, locking and discovery tests");
        TestRunner runner = new TestRunner();
        SerializationTests.register(runner);
        StoreTests.register(runner);
        RegistryTests.register(runner);
        SettingsTests.register(runner);
        LockTests.register(runner);
        FileSafetyTests.register(runner);
        ConcurrencyTests.register(runner);
        DiscoveryTests.register(runner);
        System.exit(runner.report());
    }
}
