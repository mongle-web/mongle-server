package com.mongle.backend.migration;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class MigrationCommand {
    private static final Set<String> OPERATIONS = Set.of("info", "baseline", "migrate");
    private MigrationCommand() {}

    public static boolean requested(String[] arguments) {
        return java.util.Arrays.stream(arguments).anyMatch(argument -> argument.startsWith("--db-migration"));
    }

    public static int run(String[] arguments, Map<String, String> environment, PrintStream output) {
        var json = JsonMapper.builder().build();
        try {
            if (arguments.length != 1 || !arguments[0].startsWith("--db-migration=")) {
                throw new MigrationFailure("INVALID_ARGUMENTS");
            }
            String operation = arguments[0].substring("--db-migration=".length());
            if (!OPERATIONS.contains(operation)) throw new MigrationFailure("UNSUPPORTED_OPERATION");
            var settings = MigrationSettings.fromEnvironment(environment);
            var engine = new MigrationEngine(settings.dataSource(), settings.database());
            var result = new LinkedHashMap<String, Object>(engine.execute(operation));
            result.put("event", "migration-completed");
            result.put("operation", operation);
            output.println(json.writeValueAsString(result));
            return 0;
        } catch (Exception exception) {
            // Flyway/JDBC messages can contain SQL, URLs and credentials. Never forward raw errors.
            String code = exception instanceof MigrationFailure ? exception.getMessage() : "MIGRATION_FAILED";
            output.println(json.writeValueAsString(Map.of("event", "migration-failed", "code", code)));
            return 1;
        }
    }
}
