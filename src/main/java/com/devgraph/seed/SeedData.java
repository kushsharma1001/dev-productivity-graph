package com.devgraph.seed;

import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;
import com.devgraph.graph.GraphStore;

import java.util.Map;

/**
 * A small, hand-crafted delivery graph built with intent. The topology is
 * designed so that every analytic returns an interesting, explainable answer:
 *
 * <ul>
 *   <li><b>Knowledge silo / bus factor:</b> repo {@code repo-payments} has a file
 *       {@code crypto.go} touched only by Dana — a pure silo — and Dana dominates
 *       most of the repo, giving it a bus factor of 1.</li>
 *   <li><b>Collaboration clusters:</b> Alice/Bob/Carol review each other on
 *       {@code repo-web} (one cluster); Dana/Erin work {@code repo-payments}
 *       (a second, separate cluster). Frank is a bridge who reviews across both.</li>
 *   <li><b>Blame cascade:</b> incident INC-1 traces back through a deployment and
 *       build to Bob's PR and commit that touched {@code checkout.js}.</li>
 * </ul>
 *
 * Keeping the data deterministic (no timestamps that vary per run) means the
 * tests can assert exact structural facts.
 */
public final class SeedData {

    private SeedData() {
    }

    public static void load(GraphStore g) {
        // ---- Developers ----
        dev(g, "dev-alice", "Alice");
        dev(g, "dev-bob", "Bob");
        dev(g, "dev-carol", "Carol");
        dev(g, "dev-dana", "Dana");
        dev(g, "dev-erin", "Erin");
        dev(g, "dev-frank", "Frank");

        // ---- Repositories ----
        repo(g, "repo-web", "web-frontend");
        repo(g, "repo-payments", "payments-service");

        // ---- Files ----
        file(g, "file-checkout", "web/checkout.js", "repo-web");
        file(g, "file-cart", "web/cart.js", "repo-web");
        file(g, "file-nav", "web/nav.js", "repo-web");
        file(g, "file-charge", "payments/charge.go", "repo-payments");
        file(g, "file-refund", "payments/refund.go", "repo-payments");
        // The silo: crypto.go — only Dana ever touches it.
        file(g, "file-crypto", "payments/crypto.go", "repo-payments");

        // ================= repo-web collaboration cluster =================
        // Alice, Bob, Carol co-edit web files and review each other.
        authorTouch(g, "dev-alice", "c1", "pr-1", "file-checkout", "file-cart");
        authorTouch(g, "dev-bob", "c2", "pr-2", "file-checkout", "file-nav");
        authorTouch(g, "dev-carol", "c3", "pr-3", "file-cart", "file-nav");

        // Reviews (human collaboration): reviewer -REVIEWED-> PR
        review(g, "dev-bob", "pr-1");   // Bob reviewed Alice's PR
        review(g, "dev-carol", "pr-1"); // Carol reviewed Alice's PR
        review(g, "dev-alice", "pr-2"); // Alice reviewed Bob's PR
        review(g, "dev-carol", "pr-2"); // Carol reviewed Bob's PR
        review(g, "dev-alice", "pr-3"); // Alice reviewed Carol's PR

        // ================= repo-payments cluster (siloed) =================
        // Dana dominates payments; touches charge, refund AND the silo crypto.
        authorTouch(g, "dev-dana", "c4", "pr-4", "file-charge", "file-crypto");
        authorTouch(g, "dev-dana", "c5", "pr-5", "file-refund", "file-crypto");
        authorTouch(g, "dev-erin", "c6", "pr-6", "file-charge"); // Erin touches charge only

        review(g, "dev-erin", "pr-4"); // Erin reviewed Dana
        review(g, "dev-dana", "pr-6"); // Dana reviewed Erin

        // Frank is a cross-team bridge: reviews in both repos.
        review(g, "dev-frank", "pr-2"); // web
        review(g, "dev-frank", "pr-4"); // payments

        // ================= build / deploy / incident chain =================
        // Bob's pr-2 triggered a build that deployed and caused an incident.
        node(g, "build-1", NodeType.BUILD, Map.of("status", "GREEN", "number", 101));
        node(g, "deploy-1", NodeType.DEPLOYMENT, Map.of("env", "production"));
        node(g, "inc-1", NodeType.INCIDENT,
                Map.of("title", "Checkout 500s after deploy", "severity", "SEV1"));

        g.addEdge(new Edge("pr-2", "build-1", EdgeType.TRIGGERED));
        g.addEdge(new Edge("build-1", "deploy-1", EdgeType.PRODUCED));
        g.addEdge(new Edge("deploy-1", "inc-1", EdgeType.CAUSED));

        // A second, healthy build from pr-1 that produced no incident.
        node(g, "build-2", NodeType.BUILD, Map.of("status", "GREEN", "number", 102));
        node(g, "deploy-2", NodeType.DEPLOYMENT, Map.of("env", "production"));
        g.addEdge(new Edge("pr-1", "build-2", EdgeType.TRIGGERED));
        g.addEdge(new Edge("build-2", "deploy-2", EdgeType.PRODUCED));

        // A failed build (test failure) from pr-3 — no deployment produced.
        node(g, "build-3", NodeType.BUILD,
                Map.of("status", "RED", "number", 103, "failedTests", 4));
        g.addEdge(new Edge("pr-3", "build-3", EdgeType.TRIGGERED));
    }

    // ---- small builders ----

    private static void dev(GraphStore g, String id, String name) {
        node(g, id, NodeType.DEVELOPER, Map.of("name", name));
    }

    private static void repo(GraphStore g, String id, String name) {
        node(g, id, NodeType.REPOSITORY, Map.of("name", name));
    }

    private static void file(GraphStore g, String id, String path, String repoId) {
        node(g, id, NodeType.FILE, Map.of("path", path));
        g.addEdge(new Edge(id, repoId, EdgeType.IN_REPO));
    }

    private static void review(GraphStore g, String reviewerId, String prId) {
        g.addEdge(new Edge(reviewerId, prId, EdgeType.REVIEWED, Map.of("approved", true)));
    }

    /**
     * Create a commit authored by {@code devId}, belonging to {@code prId}, that
     * touches the given files. Wires AUTHORED, PART_OF and TOUCHES edges.
     */
    private static void authorTouch(GraphStore g, String devId, String commitId, String prId, String... fileIds) {
        node(g, commitId, NodeType.COMMIT, Map.of("message", commitId));
        g.getNode(prId).or(() -> {
            node(g, prId, NodeType.PULL_REQUEST, Map.of("title", prId));
            return g.getNode(prId);
        });
        g.addEdge(new Edge(devId, commitId, EdgeType.AUTHORED));
        g.addEdge(new Edge(commitId, prId, EdgeType.PART_OF));
        for (String fileId : fileIds) {
            g.addEdge(new Edge(commitId, fileId, EdgeType.TOUCHES, Map.of("lines", 20)));
        }
    }

    private static void node(GraphStore g, String id, NodeType type, Map<String, Object> props) {
        if (g.getNode(id).isEmpty()) {
            g.addNode(new Node(id, type, props));
        }
    }
}
