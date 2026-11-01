package zhaolong.dai.nav.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Transactional one-off importer for the Guangzhou road network and POIs. */
public final class GuangzhouDataImporter {

    private static final int BATCH_SIZE = 5_000;
    private static final Set<String> NON_WALKABLE_ROAD_TYPES = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link"
    );

    private GuangzhouDataImporter() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> config = loadConfiguration();
        requireConfirmation(config);

        String targetUrl = required(config, "DATABASE_URL");
        String targetUsername = required(config, "DATABASE_USERNAME");
        String targetPassword = required(config, "DATABASE_PASSWORD");
        String sourceUrl = config.getOrDefault(
                "SOURCE_DATABASE_URL",
                replaceDatabaseName(targetUrl, "nav_map_db")
        );
        String sourceUsername = config.getOrDefault("SOURCE_DATABASE_USERNAME", targetUsername);
        String sourcePassword = config.getOrDefault("SOURCE_DATABASE_PASSWORD", targetPassword);

        if (targetUrl.equals(sourceUrl)) {
            throw new IllegalArgumentException("Source and target database URLs must be different");
        }

        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
             Connection target = DriverManager.getConnection(targetUrl, targetUsername, targetPassword)) {
            source.setReadOnly(true);
            source.setAutoCommit(false);
            target.setAutoCommit(false);
            verifySource(source);
            verifyTarget(target);

            try {
                SourceCounts counts = sourceCounts(source);
                System.out.printf(
                        "Validated source: %,d nodes, %,d edges, %,d POIs%n",
                        counts.nodes(), counts.edges(), counts.pois()
                );

                truncateTarget(target);
                long nodeCount = importNodes(source, target);
                ImportEdgesResult edgeResult = importEdges(source, target);
                long poiCount = importPois(source, target);
                finishDataset(target);
                target.commit();

                System.out.printf(
                        "Import committed: %,d nodes, %,d edges, %,d POIs; skipped %,d self-loop edges%n",
                        nodeCount, edgeResult.imported(), poiCount, edgeResult.skippedSelfLoops()
                );
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            }
        }
    }

    private static long importNodes(Connection source, Connection target) throws SQLException {
        String selectSql = """
                SELECT id, osm_id, ST_AsEWKB(the_geom)
                FROM public.ways_vertices_pgr
                ORDER BY id
                """;
        String insertSql = """
                INSERT INTO public.road_node (id, osm_id, geom, network_version)
                VALUES (?, ?, ST_GeomFromEWKB(?), 1)
                """;

        try (PreparedStatement select = streamingStatement(source, selectSql);
             PreparedStatement insert = target.prepareStatement(insertSql);
             ResultSet rows = select.executeQuery()) {
            long count = 0;
            while (rows.next()) {
                insert.setLong(1, rows.getLong("id"));
                setNullableLong(insert, 2, rows, "osm_id");
                insert.setBytes(3, rows.getBytes(3));
                insert.addBatch();
                executeBatch(insert, ++count);
            }
            insert.executeBatch();
            return count;
        }
    }

    private static ImportEdgesResult importEdges(Connection source, Connection target) throws SQLException {
        String selectSql = """
                SELECT w.gid, w.osm_id, w.tag_id, w.source, w.target,
                       w.source_osm, w.target_osm, w.name,
                       c.tag_value AS road_type, w.rule, w.one_way, w.oneway,
                       w.length_m, w.maxspeed_forward, w.maxspeed_backward,
                       w.priority, w.cost, w.reverse_cost, w.cost_s,
                       w.reverse_cost_s, ST_AsEWKB(w.the_geom)
                FROM public.ways w
                LEFT JOIN public.configuration c ON c.tag_id = w.tag_id
                ORDER BY w.gid
                """;
        String insertSql = """
                INSERT INTO public.road_edge (
                    id, osm_id, tag_id, source, target, source_osm, target_osm,
                    name, road_type, rule, oneway_code, oneway_raw, length_m,
                    maxspeed_forward, maxspeed_backward, priority,
                    routing_cost, routing_reverse_cost,
                    driving_cost_s, driving_reverse_s,
                    walking_cost_s, walking_reverse_s,
                    access_car, access_walk, coordinate_status, status,
                    network_version, geom
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 'projected_3857', 'active', 1, ST_GeomFromEWKB(?)
                )
                """;

        try (PreparedStatement select = streamingStatement(source, selectSql);
             PreparedStatement insert = target.prepareStatement(insertSql);
             ResultSet rows = select.executeQuery()) {
            long imported = 0;
            long skippedSelfLoops = 0;
            while (rows.next()) {
                long sourceNode = rows.getLong("source");
                long targetNode = rows.getLong("target");
                if (sourceNode == targetNode) {
                    skippedSelfLoops++;
                    continue;
                }

                String roadType = rows.getString("road_type");
                double lengthMeters = rows.getDouble("length_m");
                double drivingCost = rows.getDouble("cost_s");
                double drivingReverseCost = rows.getDouble("reverse_cost_s");
                boolean accessCar = drivingCost > 0 || drivingReverseCost > 0;
                boolean accessWalk = roadType == null || !NON_WALKABLE_ROAD_TYPES.contains(roadType);
                double walkingCost = accessWalk ? lengthMeters / 1.4 : -1.0;

                insert.setLong(1, rows.getLong("gid"));
                setNullableLong(insert, 2, rows, "osm_id");
                setNullableInt(insert, 3, rows, "tag_id");
                insert.setLong(4, sourceNode);
                insert.setLong(5, targetNode);
                setNullableLong(insert, 6, rows, "source_osm");
                setNullableLong(insert, 7, rows, "target_osm");
                insert.setString(8, defaultRoadName(rows.getString("name")));
                insert.setString(9, roadType);
                insert.setString(10, rows.getString("rule"));
                setNullableInt(insert, 11, rows, "one_way");
                insert.setString(12, rows.getString("oneway"));
                insert.setDouble(13, lengthMeters);
                setNullableDouble(insert, 14, rows, "maxspeed_forward");
                setNullableDouble(insert, 15, rows, "maxspeed_backward");
                setNullableDouble(insert, 16, rows, "priority");
                setNullableDouble(insert, 17, rows, "cost");
                setNullableDouble(insert, 18, rows, "reverse_cost");
                insert.setDouble(19, drivingCost);
                insert.setDouble(20, drivingReverseCost);
                insert.setDouble(21, walkingCost);
                insert.setDouble(22, walkingCost);
                insert.setBoolean(23, accessCar);
                insert.setBoolean(24, accessWalk);
                insert.setBytes(25, rows.getBytes(21));
                insert.addBatch();
                executeBatch(insert, ++imported);
            }
            insert.executeBatch();
            return new ImportEdgesResult(imported, skippedSelfLoops);
        }
    }

    private static long importPois(Connection source, Connection target) throws SQLException {
        String selectSql = """
                SELECT id, name, category, basic_category, confidence, address, longitude, latitude
                FROM public.poi_stage
                ORDER BY id
                """;
        String insertSql = """
                INSERT INTO public.poi (
                    source_id, name, context, category, basic_category, confidence,
                    address, geom, coordinate_status, status, data_version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    'projected_3857', 'active', 1
                )
                """;

        try (PreparedStatement select = streamingStatement(source, selectSql);
             PreparedStatement insert = target.prepareStatement(insertSql);
             ResultSet rows = select.executeQuery()) {
            long count = 0;
            while (rows.next()) {
                String category = rows.getString("category");
                String basicCategory = rows.getString("basic_category");
                String address = rows.getString("address");

                insert.setObject(1, UUID.fromString(rows.getString("id")));
                insert.setString(2, rows.getString("name"));
                insert.setString(3, poiContext(category, basicCategory, address));
                insert.setString(4, category);
                insert.setString(5, basicCategory);
                setNullableDouble(insert, 6, rows, "confidence");
                insert.setString(7, address);
                insert.setDouble(8, rows.getDouble("longitude"));
                insert.setDouble(9, rows.getDouble("latitude"));
                insert.addBatch();
                executeBatch(insert, ++count);
            }
            insert.executeBatch();
            return count;
        }
    }

    private static void verifySource(Connection source) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT to_regclass('public.ways') IS NOT NULL
                        AND to_regclass('public.ways_vertices_pgr') IS NOT NULL
                        AND to_regclass('public.poi_stage') IS NOT NULL
                        AND to_regclass('public.configuration') IS NOT NULL
                     """)) {
            result.next();
            if (!result.getBoolean(1)) {
                throw new IllegalStateException("Source database is missing Guangzhou source tables");
            }
        }
    }

    private static void verifyTarget(Connection target) throws SQLException {
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT EXISTS (
                         SELECT 1 FROM public.flyway_schema_history
                         WHERE version = '14' AND success
                     )
                     """)) {
            result.next();
            if (!result.getBoolean(1)) {
                throw new IllegalStateException("Target database must be migrated through Flyway V14 first");
            }
        }
    }

    private static SourceCounts sourceCounts(Connection source) throws SQLException {
        try (Statement statement = source.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT (SELECT COUNT(*) FROM public.ways_vertices_pgr),
                            (SELECT COUNT(*) FROM public.ways),
                            (SELECT COUNT(*) FROM public.poi_stage)
                     """)) {
            result.next();
            return new SourceCounts(result.getLong(1), result.getLong(2), result.getLong(3));
        }
    }

    private static void truncateTarget(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS migration_backup");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS migration_backup.road_node AS
                    TABLE public.road_node WITH DATA
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS migration_backup.road_edge AS
                    TABLE public.road_edge WITH DATA
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS migration_backup.poi AS
                    TABLE public.poi WITH DATA
                    """);
            statement.execute(
                    "TRUNCATE TABLE public.road_edge, public.road_node, public.poi RESTART IDENTITY"
            );
            System.out.println("Preserved the pre-import dataset in schema migration_backup");
        }
    }

    private static void finishDataset(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("""
                    SELECT setval(
                        pg_get_serial_sequence('public.road_edge', 'id'),
                        COALESCE((SELECT MAX(id) FROM public.road_edge), 1), true
                    )
                    """);
            statement.execute("SELECT public.bump_dataset_revision('road_network')");
            statement.execute("SELECT public.bump_dataset_revision('poi')");
            statement.execute("SELECT public.refresh_road_edge_lod()");
            statement.execute("SELECT public.bump_dataset_revision('map')");
            statement.execute("ANALYZE public.road_node");
            statement.execute("ANALYZE public.road_edge");
            statement.execute("ANALYZE public.poi");
        }
    }

    private static PreparedStatement streamingStatement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY
        );
        statement.setFetchSize(BATCH_SIZE);
        return statement;
    }

    private static void executeBatch(PreparedStatement statement, long count) throws SQLException {
        if (count % BATCH_SIZE == 0) {
            statement.executeBatch();
            System.out.printf("Processed %,d rows%n", count);
        }
    }

    private static void setNullableLong(
            PreparedStatement statement, int parameter, ResultSet rows, String column
    ) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull()) statement.setNull(parameter, java.sql.Types.BIGINT);
        else statement.setLong(parameter, value);
    }

    private static void setNullableInt(
            PreparedStatement statement, int parameter, ResultSet rows, String column
    ) throws SQLException {
        int value = rows.getInt(column);
        if (rows.wasNull()) statement.setNull(parameter, java.sql.Types.INTEGER);
        else statement.setInt(parameter, value);
    }

    private static void setNullableDouble(
            PreparedStatement statement, int parameter, ResultSet rows, String column
    ) throws SQLException {
        double value = rows.getDouble(column);
        if (rows.wasNull()) statement.setNull(parameter, java.sql.Types.DOUBLE);
        else statement.setDouble(parameter, value);
    }

    private static String defaultRoadName(String name) {
        return name == null || name.isBlank() ? "未命名道路" : name;
    }

    private static String poiContext(String category, String basicCategory, String address) {
        String type = category != null ? category : basicCategory;
        if (type == null) return address;
        if (address == null || address.isBlank()) return type;
        return type + " · " + address;
    }

    private static Map<String, String> loadConfiguration() throws Exception {
        Map<String, String> result = new HashMap<>();
        loadEnvFile(Path.of(".env"), result);
        loadEnvFile(Path.of("..", ".env"), result);
        System.getenv().forEach((key, value) -> {
            if (value != null && !value.isBlank()) result.put(key, value);
        });
        return result;
    }

    private static void loadEnvFile(Path path, Map<String, String> target) throws Exception {
        if (!Files.isRegularFile(path)) return;
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            String[] pair = trimmed.split("=", 2);
            target.putIfAbsent(pair[0].strip(), unquote(pair[1].strip()));
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void requireConfirmation(Map<String, String> config) {
        if (!"replace".equals(config.get("GUANGZHOU_IMPORT_CONFIRM"))) {
            throw new IllegalStateException(
                    "Set GUANGZHOU_IMPORT_CONFIRM=replace to acknowledge replacement of road_node, road_edge and poi"
            );
        }
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value;
    }

    private static String replaceDatabaseName(String jdbcUrl, String databaseName) {
        int queryIndex = jdbcUrl.indexOf('?');
        String suffix = queryIndex >= 0 ? jdbcUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) : jdbcUrl;
        int slash = base.lastIndexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Unsupported PostgreSQL JDBC URL: " + jdbcUrl);
        return base.substring(0, slash + 1) + databaseName + suffix;
    }

    private record SourceCounts(long nodes, long edges, long pois) {
    }

    private record ImportEdgesResult(long imported, long skippedSelfLoops) {
    }
}
