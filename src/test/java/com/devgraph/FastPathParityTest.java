package com.devgraph;

import com.devgraph.domain.Direction;
import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;
import com.devgraph.graph.GraphAnalyticsQueries;
import com.devgraph.graph.GraphStore;
import com.devgraph.graph.InMemoryGraphStore;
import com.devgraph.seed.SeedData;
import com.devgraph.service.Insights;
import com.devgraph.service.ProductivityAnalytics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The accelerated analytics ({@code collaborations}, {@code collaborationClusters},
 * {@code blameCascade}, {@code shortestPath}) take a different code path when the
 * store implements {@link GraphAnalyticsQueries}. The production
 * {@code Neo4jGraphStore} does; the plain {@link InMemoryGraphStore} does not, so
 * the other unit tests only ever exercise the fallback.
 *
 * <p>This test closes that gap: {@link CapableStore} wraps the in-memory graph and
 * <em>also</em> implements the capability (computed independently here), so the
 * exact same seed graph is analysed once via the fast path and once via the
 * fallback. The results must be identical — that is what proves the single-query
 * optimisation did not change behaviour.
 */
class FastPathParityTest {

    private ProductivityAnalytics fallback; // plain in-memory store (generic primitives)
    private ProductivityAnalytics fast;     // capability-backed store (single-query path)

    @BeforeEach
    void setUp() {
        GraphStore plain = new InMemoryGraphStore();
        SeedData.load(plain);
        fallback = new ProductivityAnalytics(plain);

        GraphStore capable = new CapableStore(loadInto(new InMemoryGraphStore()));
        fast = new ProductivityAnalytics(capable);
    }

    private static InMemoryGraphStore loadInto(InMemoryGraphStore g) {
        SeedData.load(g);
        return g;
    }

    @Test
    @DisplayName("collaborations: fast path == fallback")
    void collaborationsParity() {
        assertEquals(fallback.collaborations(1.0).toString(),
                fast.collaborations(1.0).toString());
    }

    @Test
    @DisplayName("teams (clusters): fast path == fallback")
    void clustersParity() {
        assertEquals(fallback.collaborationClusters(1.0).toString(),
                fast.collaborationClusters(1.0).toString());
    }

    @Test
    @DisplayName("blame cascade: fast path == fallback")
    void blameParity() {
        Insights.BlameCascade a = fallback.blameCascade("inc-1");
        Insights.BlameCascade b = fast.blameCascade("inc-1");
        assertEquals(a.incidentTitle(), b.incidentTitle());
        // Order-independent comparison of the implicated sets.
        assertEquals(new HashSet<>(a.implicatedCommits()), new HashSet<>(b.implicatedCommits()));
        assertEquals(new HashSet<>(a.implicatedFiles()), new HashSet<>(b.implicatedFiles()));
        assertEquals(new HashSet<>(a.deploymentPath()), new HashSet<>(b.deploymentPath()));
        assertEquals(a.implicatedDevelopers().toString(), b.implicatedDevelopers().toString());
    }

    @Test
    @DisplayName("shortest path: fast path == fallback (nodes, degrees, hop labels)")
    void pathParity() {
        Insights.PathResult a = fallback.shortestPath("dev-frank", "inc-1");
        Insights.PathResult b = fast.shortestPath("dev-frank", "inc-1");
        assertEquals(a.connected(), b.connected());
        assertEquals(a.degrees(), b.degrees());
        assertEquals(a.path(), b.path());
        assertEquals(a.hops().toString(), b.hops().toString());
    }

    @Test
    @DisplayName("shortest path: disconnected reported identically")
    void pathParityDisconnected() {
        Insights.PathResult a = fallback.shortestPath("dev-alice", "does-not-exist");
        Insights.PathResult b = fast.shortestPath("dev-alice", "does-not-exist");
        assertEquals(a.connected(), b.connected());
        assertEquals(a.degrees(), b.degrees());
    }

    // ------------------------------------------------------------------
    // A store that adds the capability by traversing the in-memory graph
    // directly (independent of ProductivityAnalytics' own fallback logic).
    // ------------------------------------------------------------------

    private static final class CapableStore implements GraphStore, GraphAnalyticsQueries {
        private final InMemoryGraphStore delegate;

        CapableStore(InMemoryGraphStore delegate) {
            this.delegate = delegate;
        }

        // ---- GraphStore: pure delegation ----
        @Override public Node addNode(Node node) { return delegate.addNode(node); }
        @Override public Edge addEdge(Edge edge) { return delegate.addEdge(edge); }
        @Override public Optional<Node> getNode(String id) { return delegate.getNode(id); }
        @Override public Collection<Node> nodesOfType(NodeType type) { return delegate.nodesOfType(type); }
        @Override public List<Edge> edges(String nodeId, Direction dir, EdgeType... t) { return delegate.edges(nodeId, dir, t); }
        @Override public List<String> neighbors(String nodeId, Direction dir, EdgeType... t) { return delegate.neighbors(nodeId, dir, t); }
        @Override public int nodeCount() { return delegate.nodeCount(); }
        @Override public int edgeCount() { return delegate.edgeCount(); }

        // ---- GraphAnalyticsQueries: independent traversal ----
        @Override
        public List<FileOwnershipRow> fileOwnership(String repositoryId) {
            List<FileOwnershipRow> rows = new ArrayList<>();
            for (Node f : delegate.nodesOfType(NodeType.FILE)) {
                List<String> repos = delegate.neighbors(f.id(), Direction.OUT, EdgeType.IN_REPO);
                String repo = repos.isEmpty() ? null : repos.get(0);
                if (repositoryId != null && !repositoryId.equals(repo)) {
                    continue;
                }
                Map<String, Integer> byDev = new HashMap<>();
                for (String c : delegate.neighbors(f.id(), Direction.IN, EdgeType.TOUCHES)) {
                    for (String d : delegate.neighbors(c, Direction.IN, EdgeType.AUTHORED)) {
                        byDev.merge(d, 1, Integer::sum);
                    }
                }
                if (byDev.isEmpty()) {
                    rows.add(new FileOwnershipRow(repo, f.id(), f.str("path"), null, null, 0));
                } else {
                    byDev.forEach((d, n) -> rows.add(new FileOwnershipRow(
                            repo, f.id(), f.str("path"), d, nameOf(d), n)));
                }
            }
            return rows;
        }

        @Override
        public List<DevRelationRow> developerRelations() {
            List<DevRelationRow> rows = new ArrayList<>();
            for (Node d : delegate.nodesOfType(NodeType.DEVELOPER)) {
                for (String c : delegate.neighbors(d.id(), Direction.OUT, EdgeType.AUTHORED)) {
                    for (String f : delegate.neighbors(c, Direction.OUT, EdgeType.TOUCHES)) {
                        rows.add(new DevRelationRow(RelationKind.FILE_TOUCHED, d.id(), f));
                    }
                    for (String p : delegate.neighbors(c, Direction.OUT, EdgeType.PART_OF)) {
                        rows.add(new DevRelationRow(RelationKind.AUTHORED_PR, d.id(), p));
                    }
                }
                for (String p : delegate.neighbors(d.id(), Direction.OUT, EdgeType.REVIEWED)) {
                    rows.add(new DevRelationRow(RelationKind.REVIEWED_PR, d.id(), p));
                }
            }
            return rows;
        }

        @Override
        public List<BlameRow> blameRows(String incidentId) {
            List<BlameRow> rows = new ArrayList<>();
            for (String dep : delegate.neighbors(incidentId, Direction.IN, EdgeType.CAUSED)) {
                for (String b : delegate.neighbors(dep, Direction.IN, EdgeType.PRODUCED)) {
                    for (String t : delegate.neighbors(b, Direction.IN, EdgeType.TRIGGERED)) {
                        Optional<Node> tn = delegate.getNode(t);
                        if (tn.isEmpty()) {
                            continue;
                        }
                        List<String> commits = new ArrayList<>();
                        if (tn.get().type() == NodeType.PULL_REQUEST) {
                            commits.addAll(delegate.neighbors(t, Direction.IN, EdgeType.PART_OF));
                        } else if (tn.get().type() == NodeType.COMMIT) {
                            commits.add(t);
                        }
                        for (String c : commits) {
                            List<String> files = delegate.neighbors(c, Direction.OUT, EdgeType.TOUCHES);
                            List<String> devs = delegate.neighbors(c, Direction.IN, EdgeType.AUTHORED);
                            String dev = devs.isEmpty() ? null : devs.get(0);
                            if (files.isEmpty()) {
                                rows.add(new BlameRow(dep, b, c, null, dev, dev == null ? null : nameOf(dev)));
                            } else {
                                for (String f : files) {
                                    rows.add(new BlameRow(dep, b, c, f, dev, dev == null ? null : nameOf(dev)));
                                }
                            }
                        }
                    }
                }
            }
            return rows;
        }

        @Override
        public PathData pathBetween(String fromId, String toId) {
            if (delegate.getNode(fromId).isEmpty() || delegate.getNode(toId).isEmpty()) {
                return null;
            }
            // Independent BFS producing the same shape the Cypher query would.
            Map<String, String> pred = new HashMap<>();
            Map<String, Edge> predEdge = new HashMap<>();
            Set<String> seen = new HashSet<>();
            Deque<String> q = new ArrayDeque<>();
            q.add(fromId);
            seen.add(fromId);
            while (!q.isEmpty()) {
                String cur = q.poll();
                for (Edge e : delegate.edges(cur, Direction.BOTH)) {
                    String nxt = e.other(cur);
                    if (seen.add(nxt)) {
                        pred.put(nxt, cur);
                        predEdge.put(nxt, e);
                        if (nxt.equals(toId)) {
                            return reconstruct(fromId, toId, pred, predEdge);
                        }
                        q.add(nxt);
                    }
                }
            }
            return null;
        }

        private PathData reconstruct(String from, String to,
                                     Map<String, String> pred, Map<String, Edge> predEdge) {
            Deque<String> stack = new ArrayDeque<>();
            String cur = to;
            while (cur != null && !cur.equals(from)) {
                stack.push(cur);
                cur = pred.get(cur);
            }
            stack.push(from);
            List<String> nodeIds = new ArrayList<>(stack);
            List<PathEdge> edges = new ArrayList<>();
            for (int i = 0; i + 1 < nodeIds.size(); i++) {
                Edge e = predEdge.get(nodeIds.get(i + 1));
                edges.add(new PathEdge(e.type().name(), e.from(), e.to()));
            }
            return new PathData(nodeIds, edges);
        }

        private String nameOf(String devId) {
            return delegate.getNode(devId).map(n -> n.str("name")).orElse(devId);
        }
    }
}
