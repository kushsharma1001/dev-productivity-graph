package com.devgraph.graph;

import com.devgraph.domain.Direction;
import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.neo4j.driver.Values.parameters;

/**
 * A Neo4j-backed implementation of {@link GraphStore}.
 *
 * <p>Mapping model:
 * <ul>
 *   <li>A {@link Node} becomes a Neo4j node whose single label is the {@link NodeType}
 *       name, with an indexed {@code id} property and the property bag spread on top.</li>
 *   <li>An {@link Edge} becomes a relationship whose type is the {@link EdgeType} name,
 *       carrying the edge's property bag.</li>
 * </ul>
 *
 * <p>Because the driver only exposes relationship endpoints as internal element ids,
 * every query that needs to rebuild an {@link Edge} also returns the endpoints'
 * {@code id} properties explicitly ({@code startNode(r).id} / {@code endNode(r).id}).
 */
public final class Neo4jGraphStore implements GraphStore, GraphAnalyticsQueries, AutoCloseable {

    /**
     * Whole-file-ownership for one repository in a single round-trip. Fixed literal
     * with a single {@code $repoId} parameter — no string concatenation. The
     * OPTIONAL MATCH keeps files that have no commits (they come back with a null
     * developer), matching the generic-primitive fallback's behaviour.
     */
    private static final String FILE_OWNERSHIP_BY_REPO = """
            MATCH (r:REPOSITORY {id:$repoId})<-[:IN_REPO]-(f:FILE)
            OPTIONAL MATCH (f)<-[:TOUCHES]-(c:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
            RETURN r.id AS repoId, f.id AS fileId, f.path AS filePath,
                   d.id AS devId, d.name AS devName, count(c) AS commits
            """;

    /** Same aggregation across every repository; used for the whole-graph scope. */
    private static final String FILE_OWNERSHIP_ALL = """
            MATCH (f:FILE)-[:IN_REPO]->(r:REPOSITORY)
            OPTIONAL MATCH (f)<-[:TOUCHES]-(c:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
            RETURN r.id AS repoId, f.id AS fileId, f.path AS filePath,
                   d.id AS devId, d.name AS devName, count(c) AS commits
            """;

    /**
     * All developer-to-item associations behind the collaboration graph, in one
     * query. Three fixed sub-patterns UNION'd together — no concatenation, no
     * parameters needed (it is graph-wide). {@code kind} tags each row so the
     * caller can bucket them.
     */
    private static final String DEVELOPER_RELATIONS = """
            MATCH (d:DEVELOPER)-[:AUTHORED]->(:COMMIT)-[:TOUCHES]->(f:FILE)
            RETURN 'FILE_TOUCHED' AS kind, d.id AS devId, f.id AS itemId
            UNION
            MATCH (d:DEVELOPER)-[:AUTHORED]->(:COMMIT)-[:PART_OF]->(p:PULL_REQUEST)
            RETURN 'AUTHORED_PR' AS kind, d.id AS devId, p.id AS itemId
            UNION
            MATCH (d:DEVELOPER)-[:REVIEWED]->(p:PULL_REQUEST)
            RETURN 'REVIEWED_PR' AS kind, d.id AS devId, p.id AS itemId
            """;

    /**
     * The full blame cascade for an incident in one round-trip. Fixed literal with
     * a single {@code $incidentId} parameter. Walks the reverse chain and fans out
     * over touched files and authoring developers; OPTIONAL MATCH keeps commits that
     * have no file/author (they come back with nulls). Handles both PR-triggered and
     * commit-triggered builds via the variable-length {@code TRIGGERED|PART_OF}.
     */
    private static final String BLAME_CASCADE = """
            MATCH (i:INCIDENT {id:$incidentId})<-[:CAUSED]-(dep:DEPLOYMENT)
                  <-[:PRODUCED]-(b:BUILD)<-[:TRIGGERED]-(t)
            MATCH (t)<-[:PART_OF*0..1]-(c:COMMIT)
            OPTIONAL MATCH (c)-[:TOUCHES]->(f:FILE)
            OPTIONAL MATCH (c)<-[:AUTHORED]-(d:DEVELOPER)
            RETURN dep.id AS deploymentId, b.id AS buildId, c.id AS commitId,
                   f.id AS fileId, d.id AS devId, d.name AS devName
            """;

    /**
     * Shortest undirected path between two nodes, as one query. Fixed literal with
     * {@code $fromId}/{@code $toId} parameters; returns node ids in order and the
     * relationships (with their stored endpoints) so the caller can label hops.
     */
    private static final String SHORTEST_PATH = """
            MATCH (a {id:$fromId}), (b {id:$toId}),
                  p = shortestPath((a)-[*..15]-(b))
            RETURN [n IN nodes(p) | n.id] AS nodeIds,
                   [r IN relationships(p) |
                       {type: type(r), fromId: startNode(r).id, toId: endNode(r).id}]
                       AS edges
            """;

    private final Driver driver;

    public Neo4jGraphStore(Driver driver) {
        this.driver = driver;
        // Uniqueness + fast lookup on id, one constraint per label.
        try (Session s = driver.session()) {
            for (NodeType t : NodeType.values()) {
                s.run("CREATE CONSTRAINT IF NOT EXISTS FOR (n:" + t
                        + ") REQUIRE n.id IS UNIQUE");
            }
        }
    }

    @Override
    public Node addNode(Node node) {
        Map<String, Object> props = new HashMap<>(node.properties());
        props.put("id", node.id());
        try (Session s = driver.session()) {
            // Labels cannot be parameterized in Cypher — the enum name is injected
            // directly, which is safe because it is not user-supplied input.
            s.executeWrite(tx -> tx.run(
                    "MERGE (n:" + node.type() + " {id:$id}) SET n += $props",
                    parameters("id", node.id(), "props", props)).consume());
        }
        return node;
    }

    @Override
    public Edge addEdge(Edge edge) {
        try (Session s = driver.session()) {
            var counters = s.executeWrite(tx -> tx.run(
                    "MATCH (a {id:$from}), (b {id:$to}) "
                            + "MERGE (a)-[r:" + edge.type() + "]->(b) SET r += $props",
                    parameters("from", edge.from(), "to", edge.to(),
                            "props", edge.properties()))
                    .consume().counters());
            // Preserve the in-memory store's fail-fast contract: if no relationship
            // was created (and none already existed), an endpoint was missing.
            if (counters.relationshipsCreated() == 0
                    && !relationshipExists(s, edge)) {
                throw new IllegalArgumentException(
                        "Could not create edge; unknown endpoint(s): "
                                + edge.from() + " -> " + edge.to());
            }
        }
        return edge;
    }

    private boolean relationshipExists(Session s, Edge edge) {
        return s.executeRead(tx -> tx.run(
                "MATCH (a {id:$from})-[r:" + edge.type() + "]->(b {id:$to}) "
                        + "RETURN count(r) AS c",
                parameters("from", edge.from(), "to", edge.to()))
                .single().get("c").asInt() > 0);
    }

    @Override
    public Optional<Node> getNode(String id) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> {
                var res = tx.run("MATCH (n {id:$id}) RETURN n", parameters("id", id));
                return res.hasNext()
                        ? Optional.of(toNode(res.next().get("n").asNode()))
                        : Optional.<Node>empty();
            });
        }
    }

    @Override
    public Collection<Node> nodesOfType(NodeType type) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> tx.run("MATCH (n:" + type + ") RETURN n")
                    .list(r -> toNode(r.get("n").asNode())));
        }
    }

    @Override
    public List<Edge> edges(String nodeId, Direction direction, EdgeType... types) {
        String pattern = switch (direction) {
            case OUT  -> "(n {id:$id})-[r]->(m)";
            case IN   -> "(n {id:$id})<-[r]-(m)";
            case BOTH -> "(n {id:$id})-[r]-(m)";
        };
        String filter = (types == null || types.length == 0)
                ? ""
                : " WHERE type(r) IN $types";
        List<String> typeNames = (types == null)
                ? List.of()
                : Arrays.stream(types).map(Enum::name).collect(Collectors.toList());

        try (Session s = driver.session()) {
            return s.executeRead(tx -> tx.run(
                    "MATCH " + pattern + filter
                            + " RETURN r, startNode(r).id AS fromId, endNode(r).id AS toId",
                    parameters("id", nodeId, "types", typeNames))
                    .list(rec -> {
                        String from = rec.get("fromId").asString();
                        String to = rec.get("toId").asString();
                        var rel = rec.get("r").asRelationship();
                        return new Edge(from, to,
                                EdgeType.valueOf(rel.type()),
                                new HashMap<>(rel.asMap()));
                    }));
        }
    }

    @Override
    public List<String> neighbors(String nodeId, Direction direction, EdgeType... types) {
        return edges(nodeId, direction, types).stream()
                .map(e -> e.other(nodeId))
                .collect(Collectors.toList());
    }

    @Override
    public int nodeCount() {
        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run("MATCH (n) RETURN count(n) AS c").single().get("c").asInt());
        }
    }

    @Override
    public int edgeCount() {
        try (Session s = driver.session()) {
            return s.executeRead(tx ->
                    tx.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asInt());
        }
    }

    @Override
    public List<FileOwnershipRow> fileOwnership(String repositoryId) {
        // Whole-graph scope when repositoryId is null; both queries are fixed
        // literals and the value (when present) is bound as $repoId.
        try (Session s = driver.session()) {
            if (repositoryId == null) {
                return s.executeRead(tx -> tx.run(FILE_OWNERSHIP_ALL)
                        .list(Neo4jGraphStore::toOwnershipRow));
            }
            return s.executeRead(tx -> tx.run(
                    FILE_OWNERSHIP_BY_REPO, parameters("repoId", repositoryId))
                    .list(Neo4jGraphStore::toOwnershipRow));
        }
    }

    private static FileOwnershipRow toOwnershipRow(org.neo4j.driver.Record rec) {
        var dev = rec.get("devId");
        // count(c) is 0 for files with no commits (the OPTIONAL MATCH miss).
        int commits = rec.get("commits").asInt(0);
        return new FileOwnershipRow(
                rec.get("repoId").asString(null),
                rec.get("fileId").asString(null),
                rec.get("filePath").asString(null),
                dev.isNull() ? null : dev.asString(),
                rec.get("devName").isNull() ? null : rec.get("devName").asString(),
                commits);
    }

    @Override
    public List<DevRelationRow> developerRelations() {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> tx.run(DEVELOPER_RELATIONS).list(rec ->
                    new DevRelationRow(
                            RelationKind.valueOf(rec.get("kind").asString()),
                            rec.get("devId").asString(),
                            rec.get("itemId").asString())));
        }
    }

    @Override
    public List<BlameRow> blameRows(String incidentId) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> tx.run(
                    BLAME_CASCADE, parameters("incidentId", incidentId))
                    .list(rec -> new BlameRow(
                            rec.get("deploymentId").asString(null),
                            rec.get("buildId").asString(null),
                            rec.get("commitId").asString(null),
                            rec.get("fileId").isNull() ? null : rec.get("fileId").asString(),
                            rec.get("devId").isNull() ? null : rec.get("devId").asString(),
                            rec.get("devName").isNull() ? null : rec.get("devName").asString())));
        }
    }

    @Override
    public PathData pathBetween(String fromId, String toId) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> {
                var res = tx.run(SHORTEST_PATH, parameters("fromId", fromId, "toId", toId));
                if (!res.hasNext()) {
                    return null; // no path (or an endpoint is missing)
                }
                var rec = res.next();
                List<String> nodeIds = rec.get("nodeIds").asList(v -> v.asString());
                List<PathEdge> edges = rec.get("edges").asList(v -> {
                    var m = v.asMap();
                    return new PathEdge(
                            (String) m.get("type"),
                            (String) m.get("fromId"),
                            (String) m.get("toId"));
                });
                return new PathData(nodeIds, edges);
            });
        }
    }

    // ---- mapping helper: Neo4j node -> domain Node ----

    private Node toNode(org.neo4j.driver.types.Node n) {
        NodeType type = NodeType.valueOf(n.labels().iterator().next());
        Map<String, Object> props = new HashMap<>(n.asMap());
        String id = (String) props.remove("id"); // keep 'id' out of the property bag
        return new Node(id, type, props);
    }

    @Override
    public void close() {
        driver.close();
    }
}