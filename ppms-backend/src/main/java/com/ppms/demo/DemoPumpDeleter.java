package com.ppms.demo;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes a demo pump and every row that belongs to it, read from the database's own foreign keys so tables added by
 * later migrations are covered without changes here. A row belongs to the pump when its pump_id is the pump's, or
 * when it references a row that belongs to the pump. Rows are deleted children first; a reference cycle (such as a
 * pump's manager who works at that pump) is broken by clearing the nullable reference first.
 * Refuses, deleting nothing, when a row of another pump would go with it (a real staff member moved to the demo pump).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DemoPumpDeleter {

    private static final String PUMPS = "pump_locations";
    private static final String ID_TYPE = "int8";

    private final JdbcTemplate jdbcTemplate;

    private record ForeignKey(String childTable, String childColumn, String parentTable, String parentColumn,
                              String parentColumnType, boolean childColumnNullable) {
    }

    /** The rows of one table that belong to the pump, as "column = ANY(values)" conditions joined with OR. */
    private static final class Doomed {
        private final Map<String, Set<Object>> valuesByColumn = new LinkedHashMap<>();
        private final Map<String, String> typeByColumn = new HashMap<>();

        boolean add(String column, String type, Set<Object> values) {
            typeByColumn.put(column, type);
            return valuesByColumn.computeIfAbsent(column, key -> new HashSet<>()).addAll(values);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int delete(Long pumpId) {
        List<ForeignKey> foreignKeys = loadForeignKeys();
        Map<String, Doomed> doomedByTable = findRowsOfPump(pumpId, foreignKeys);
        refuseWhenOtherPumpsWouldLoseRows(pumpId, doomedByTable);

        Map<String, Set<Object>> idsByTable = new HashMap<>();
        Map<String, String> idTypeByTable = new HashMap<>();
        for (Map.Entry<String, Doomed> entry : doomedByTable.entrySet()) {
            String idType = columnType(entry.getKey(), "id");
            if (idType != null) {
                idTypeByTable.put(entry.getKey(), idType);
                idsByTable.put(entry.getKey(), new HashSet<>(selectValues(entry.getKey(), "id", entry.getValue())));
            }
        }

        List<ForeignKey> remaining = new ArrayList<>(foreignKeys.stream()
                .filter(key -> doomedByTable.containsKey(key.childTable()) && doomedByTable.containsKey(key.parentTable()))
                .filter(key -> !key.childTable().equals(key.parentTable()))
                .toList());
        Set<String> tablesLeft = new LinkedHashSet<>(doomedByTable.keySet());
        int deleted = 0;
        while (!tablesLeft.isEmpty()) {
            String next = tablesLeft.stream()
                    .filter(table -> remaining.stream().noneMatch(key -> key.parentTable().equals(table)
                            && tablesLeft.contains(key.childTable())))
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                breakCycle(remaining, tablesLeft, idsByTable, idTypeByTable);
                continue;
            }
            deleted += deleteRows(next, doomedByTable.get(next), idsByTable.get(next), idTypeByTable.get(next));
            tablesLeft.remove(next);
        }
        log.info("Demo pump data deleted: pump={}, tables={}, rows={}", pumpId, doomedByTable.size(), deleted);
        return deleted;
    }

    private List<ForeignKey> loadForeignKeys() {
        return jdbcTemplate.query("""
                SELECT child.relname AS child_table, child_column.attname AS child_column,
                       parent.relname AS parent_table, parent_column.attname AS parent_column,
                       parent_type.typname AS parent_column_type,
                       NOT child_column.attnotnull AS child_nullable
                FROM pg_constraint fk
                JOIN pg_class child ON child.oid = fk.conrelid
                JOIN pg_class parent ON parent.oid = fk.confrelid
                JOIN pg_namespace ns ON ns.oid = child.relnamespace
                JOIN pg_attribute child_column ON child_column.attrelid = fk.conrelid AND child_column.attnum = fk.conkey[1]
                JOIN pg_attribute parent_column ON parent_column.attrelid = fk.confrelid AND parent_column.attnum = fk.confkey[1]
                JOIN pg_type parent_type ON parent_type.oid = parent_column.atttypid
                WHERE fk.contype = 'f' AND ns.nspname = current_schema() AND cardinality(fk.conkey) = 1
                """, (rs, rowNumber) -> new ForeignKey(rs.getString("child_table"), rs.getString("child_column"),
                rs.getString("parent_table"), rs.getString("parent_column"), rs.getString("parent_column_type"),
                rs.getBoolean("child_nullable")));
    }

    /** Tables with a pump_id column, then every row reachable through foreign keys, until nothing new is found. */
    private Map<String, Doomed> findRowsOfPump(Long pumpId, List<ForeignKey> foreignKeys) {
        Map<String, Doomed> doomedByTable = new LinkedHashMap<>();
        doomedByTable.computeIfAbsent(PUMPS, table -> new Doomed()).add("id", ID_TYPE, Set.of(pumpId));
        for (String table : tablesWithColumn("pump_id")) {
            doomedByTable.computeIfAbsent(table, key -> new Doomed()).add("pump_id", ID_TYPE, Set.of(pumpId));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ForeignKey key : foreignKeys) {
                Doomed parent = doomedByTable.get(key.parentTable());
                if (parent == null) {
                    continue;
                }
                Set<Object> parentValues = new HashSet<>(selectValues(key.parentTable(), key.parentColumn(), parent));
                if (parentValues.isEmpty()) {
                    continue;
                }
                changed |= doomedByTable.computeIfAbsent(key.childTable(), table -> new Doomed())
                        .add(key.childColumn(), key.parentColumnType(), parentValues);
            }
        }
        return doomedByTable;
    }

    private void refuseWhenOtherPumpsWouldLoseRows(Long pumpId, Map<String, Doomed> doomedByTable) {
        for (Map.Entry<String, Doomed> entry : doomedByTable.entrySet()) {
            if (entry.getKey().equals(PUMPS) || columnType(entry.getKey(), "pump_id") == null) {
                continue;
            }
            Long others = queryForCount("SELECT COUNT(*) FROM " + quote(entry.getKey()) + " WHERE ("
                    + condition(entry.getValue()) + ") AND pump_id IS NOT NULL AND pump_id <> ?",
                    entry.getValue(), pumpId);
            if (others > 0) {
                log.warn("Demo pump {} not deleted: {} row(s) of other pumps in {} reference it", pumpId, others, entry.getKey());
                throw new BusinessException(ErrorCodes.DEMO_PUMP_DELETE_BLOCKED, Map.of(),
                        "The demo pump is linked to records of another pump, such as a staff member moved there. "
                                + "Move them back before deleting the demo pump.");
            }
        }
    }

    /** Clears the nullable reference with the fewest rows to clear; those rows are deleted soon after anyway. */
    private void breakCycle(List<ForeignKey> remaining, Set<String> tablesLeft, Map<String, Set<Object>> idsByTable,
                            Map<String, String> idTypeByTable) {
        ForeignKey cut = remaining.stream()
                .filter(key -> tablesLeft.contains(key.childTable()) && tablesLeft.contains(key.parentTable()))
                .filter(ForeignKey::childColumnNullable)
                .filter(key -> idsByTable.containsKey(key.childTable()))
                .min(Comparator.comparingInt(key -> idsByTable.get(key.childTable()).size()))
                .orElseThrow(() -> new IllegalStateException("Demo pump delete found a reference cycle it cannot break among "
                        + tablesLeft));
        Set<Object> ids = idsByTable.get(cut.childTable());
        if (!ids.isEmpty()) {
            jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
                try (var statement = connection.prepareStatement("UPDATE " + quote(cut.childTable()) + " SET "
                        + quote(cut.childColumn()) + " = NULL WHERE id = ANY(?)")) {
                    statement.setArray(1, connection.createArrayOf(idTypeByTable.get(cut.childTable()), ids.toArray()));
                    return statement.executeUpdate();
                }
            });
        }
        remaining.remove(cut);
    }

    private int deleteRows(String table, Doomed doomed, Set<Object> ids, String idType) {
        if (ids != null) {
            if (ids.isEmpty()) {
                return 0;
            }
            return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
                try (var statement = connection.prepareStatement("DELETE FROM " + quote(table) + " WHERE id = ANY(?)")) {
                    statement.setArray(1, connection.createArrayOf(idType, ids.toArray()));
                    return statement.executeUpdate();
                }
            });
        }
        return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM " + quote(table) + " WHERE " + condition(doomed))) {
                bindArrays(connection, statement, doomed);
                return statement.executeUpdate();
            }
        });
    }

    private List<Object> selectValues(String table, String column, Doomed doomed) {
        return jdbcTemplate.execute((ConnectionCallback<List<Object>>) connection -> {
            try (var statement = connection.prepareStatement("SELECT DISTINCT " + quote(column) + " FROM " + quote(table)
                    + " WHERE (" + condition(doomed) + ") AND " + quote(column) + " IS NOT NULL")) {
                bindArrays(connection, statement, doomed);
                List<Object> values = new ArrayList<>();
                try (var results = statement.executeQuery()) {
                    while (results.next()) {
                        values.add(results.getObject(1));
                    }
                }
                return values;
            }
        });
    }

    private Long queryForCount(String sql, Doomed doomed, Long pumpId) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                int next = bindArrays(connection, statement, doomed);
                statement.setLong(next, pumpId);
                try (var results = statement.executeQuery()) {
                    results.next();
                    return results.getLong(1);
                }
            }
        });
    }

    private static String condition(Doomed doomed) {
        List<String> parts = new ArrayList<>();
        doomed.valuesByColumn.keySet().forEach(column -> parts.add(quote(column) + " = ANY(?)"));
        return String.join(" OR ", parts);
    }

    private static int bindArrays(Connection connection, PreparedStatement statement, Doomed doomed) throws SQLException {
        int index = 1;
        for (Map.Entry<String, Set<Object>> entry : doomed.valuesByColumn.entrySet()) {
            Array array = connection.createArrayOf(doomed.typeByColumn.get(entry.getKey()), entry.getValue().toArray());
            statement.setArray(index++, array);
        }
        return index;
    }

    private List<String> tablesWithColumn(String column) {
        return jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = ?
                  AND table_name IN (SELECT table_name FROM information_schema.tables
                                     WHERE table_schema = current_schema() AND table_type = 'BASE TABLE')
                """, String.class, column);
    }

    /** The column's type name as the JDBC driver knows it (int8, uuid, varchar…), or null when the table has no such column. */
    private String columnType(String table, String column) {
        List<String> types = jdbcTemplate.queryForList("""
                SELECT udt_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
        return types.isEmpty() ? null : types.get(0);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
