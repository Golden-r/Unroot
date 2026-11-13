package org.uspace.test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.uspace.core.model.AppType;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;
import org.uspace.core.model.EnvVariable;
import org.uspace.core.model.LaunchCommand;

/** Application values used across the test classes. */
public final class Fixtures {

    /** Deliberately has nanosecond precision: {@code Instant} must survive the text round-trip exactly. */
    public static final Instant REGISTERED_AT = Instant.parse("2026-02-03T04:05:06.123456789Z");

    private Fixtures() {
    }

    public static ApplicationId id(String value) {
        return new ApplicationId(value);
    }

    /** Every field populated. */
    public static Application full() {
        return new Application(
                id("media-player"),
                "Media Player",
                "Plays things",
                AppType.PORTABLE,
                new LaunchCommand(
                        Path.of("/home/user/Apps/Media Player/run.sh"),
                        List.of("--no-sandbox", "--config", "/home/user/Apps/Media Player/conf.ini"),
                        Path.of("/home/user/Apps/Media Player/bin")),
                Path.of("/home/user/Apps/Media Player"),
                Path.of("/home/user/Apps/Media Player/icon.png"),
                List.of(new EnvVariable("JAVA_HOME", "/usr/lib/jvm/default-java"),
                        new EnvVariable("EXTRA_OPTS", "--fast")),
                REGISTERED_AT,
                "discovered:/home/user/Apps");
    }

    /** No install root, no icon, no arguments, no environment. */
    public static Application minimal(String id) {
        return new Application(
                id(id),
                "Minimal",
                "",
                AppType.BINARY,
                LaunchCommand.of(Path.of("/home/user/bin/tool")),
                null,
                null,
                List.of(),
                REGISTERED_AT,
                "manual");
    }

    /** An application with the given display name and executable, for duplicate tests. */
    public static Application named(String id, String name, Path executable) {
        return new Application(id(id), name, "", AppType.BINARY, LaunchCommand.of(executable),
                null, null, List.of(), REGISTERED_AT, "manual");
    }
}
