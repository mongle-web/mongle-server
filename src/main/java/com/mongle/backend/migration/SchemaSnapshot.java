package com.mongle.backend.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stable MySQL 8.4 catalogs: excludes counters, row data and the Flyway history table. */
public final class SchemaSnapshot {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> QUERIES = queries();

    private SchemaSnapshot() {}

    private static Map<String, String> queries() {
        var queries = new LinkedHashMap<String, String>();
        queries.put("tables", "SELECT JSON_ARRAY(TABLE_NAME,TABLE_TYPE,ENGINE,TABLE_COLLATION) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name");
        queries.put("columns", "SELECT JSON_ARRAY(TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,GENERATION_EXPRESSION) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position");
        queries.put("indexes", "SELECT JSON_ARRAY(TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,EXPRESSION,SUB_PART,COLLATION,INDEX_TYPE,IS_VISIBLE) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,index_name,seq_in_index");
        queries.put("foreignKeys", "SELECT JSON_ARRAY(k.TABLE_NAME,k.CONSTRAINT_NAME,k.COLUMN_NAME,k.ORDINAL_POSITION,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.UPDATE_RULE,r.DELETE_RULE) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name WHERE k.table_schema=DATABASE() ORDER BY k.table_name,k.constraint_name,k.ordinal_position");
        queries.put("checkConstraints", "SELECT JSON_ARRAY(t.TABLE_NAME,t.CONSTRAINT_NAME,t.ENFORCED,c.CHECK_CLAUSE) FROM information_schema.table_constraints t JOIN information_schema.check_constraints c ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE t.table_schema=DATABASE() AND t.table_name<>'flyway_schema_history' AND t.constraint_type='CHECK' ORDER BY t.table_name,t.constraint_name");
        return queries;
    }

    public static JsonNode capture(Connection connection) throws SQLException {
        var root = JSON.createObjectNode();
        for (var query : QUERIES.entrySet()) {
            var rows = root.putArray(query.getKey());
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                try (var result = statement.executeQuery(query.getValue())) {
                    while (result.next()) rows.add(JSON.readTree(result.getString(1)));
                }
            }
        }
        return root;
    }

    public static JsonNode baseline() {
        try {
            return JSON.readTree(new ClassPathResource("db/baseline/v1-schema.json")
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new MigrationFailure("BASELINE_RESOURCE_INVALID"); }
    }

    public static String fingerprint(JsonNode snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(snapshot)));
        } catch (Exception exception) { throw new MigrationFailure("SCHEMA_FINGERPRINT_FAILED"); }
    }
}
