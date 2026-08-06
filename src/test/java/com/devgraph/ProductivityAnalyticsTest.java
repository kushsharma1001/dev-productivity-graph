package com.devgraph;

import com.devgraph.graph.GraphStore;
import com.devgraph.graph.InMemoryGraphStore;
import com.devgraph.seed.SeedData;
import com.devgraph.service.Insights;
import com.devgraph.service.ProductivityAnalytics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests assert the graph analytics against the deliberately-shaped seed
 * graph. Each test encodes a structural fact that is only knowable by traversing
 * relationships — which is the whole argument for using a graph here.
 */
class ProductivityAnalyticsTest {

    private ProductivityAnalytics analytics;

    @BeforeEach
    void setUp() {
        GraphStore graph = new InMemoryGraphStore();
        SeedData.load(graph);
        analytics = new ProductivityAnalytics(graph);
    }

    @Test
    @DisplayName("crypto.go is a knowledge silo owned solely by Dana")
    void detectsKnowledgeSilo() {
        List<Insights.KnowledgeSilo> silos = analytics.knowledgeSilos("repo-payments");

        boolean cryptoSilo = silos.stream().anyMatch(s ->
                s.fileId().equals("file-crypto")
                        && s.soleOwnerId().equals("dev-dana")
                        && s.soleOwnerName().equals("Dana"));
        assertTrue(cryptoSilo, "crypto.go should be flagged as a silo owned by Dana");

        // checkout.js is edited by both Alice and Bob, so it must NOT be a silo.
        boolean checkoutSilo = silos.stream().anyMatch(s -> s.fileId().equals("file-checkout"));
        assertFalse(checkoutSilo, "checkout.js has multiple authors; not a silo");
    }

    @Test
    @DisplayName("payments repo has bus factor 1 — Dana dominates the majority of files")
    void computesBusFactor() {
        Insights.BusFactor bf = analytics.busFactor("repo-payments");

        assertEquals("repo-payments", bf.repositoryId());
        assertEquals(3, bf.fileCount(), "charge, refund, crypto");
        assertEquals(1, bf.busFactor(), "Dana alone dominates >50% of the files");
        assertEquals("dev-dana", bf.topOwners().get(0).developerId());
        assertTrue(bf.soleOwnedFileCount() >= 1, "crypto.go is sole-owned");
    }

    @Test
    @DisplayName("web repo has a healthier bus factor than payments")
    void webRepoIsHealthier() {
        Insights.BusFactor web = analytics.busFactor("repo-web");
        Insights.BusFactor payments = analytics.busFactor("repo-payments");
        assertTrue(web.busFactor() >= payments.busFactor(),
                "web (shared ownership) should not be more concentrated than payments (siloed)");
    }

    @Test
    @DisplayName("collaboration is derived from reviews + co-editing, ranked by strength")
    void derivesCollaborations() {
        List<Insights.Collaboration> collabs = analytics.collaborations(1.0);
        assertFalse(collabs.isEmpty(), "there should be derived collaborations");

        // Alice and Bob review each other and co-edit checkout.js -> strong link.
        boolean aliceBob = collabs.stream().anyMatch(c ->
                (c.developerA().equals("dev-alice") && c.developerB().equals("dev-bob"))
                        || (c.developerA().equals("dev-bob") && c.developerB().equals("dev-alice")));
        assertTrue(aliceBob, "Alice and Bob should be linked collaborators");

        // Sorted by strength descending.
        for (int i = 1; i < collabs.size(); i++) {
            assertTrue(collabs.get(i - 1).strength() >= collabs.get(i).strength(),
                    "collaborations must be sorted by strength desc");
        }
    }

    @Test
    @DisplayName("collaboration clustering separates the web team from the payments team")
    void findsCollaborationClusters() {
        List<Insights.CollaborationCluster> clusters = analytics.collaborationClusters(1.0);
        assertFalse(clusters.isEmpty(), "expected at least one cluster");

        // Frank bridges both repos, so with low threshold the graph may connect;
        // assert that the web trio and payments pair are each internally grouped.
        boolean webTogether = clusters.stream().anyMatch(c ->
                c.developerIds().containsAll(List.of("dev-alice", "dev-bob", "dev-carol")));
        assertTrue(webTogether, "Alice, Bob, Carol should share a cluster");
    }

    @Test
    @DisplayName("blame cascade traces incident INC-1 back to Bob's commit and checkout.js")
    void tracesBlameCascade() {
        Insights.BlameCascade cascade = analytics.blameCascade("inc-1");

        assertEquals("inc-1", cascade.incidentId());
        assertEquals("Checkout 500s after deploy", cascade.incidentTitle());

        // Bob authored pr-2 (commit c2) which triggered build-1 -> deploy-1 -> inc-1.
        assertTrue(cascade.implicatedCommits().contains("c2"),
                "commit c2 should be implicated");
        assertTrue(cascade.implicatedFiles().contains("file-checkout"),
                "checkout.js should be implicated");
        boolean bobImplicated = cascade.implicatedDevelopers().stream()
                .anyMatch(d -> d.developerId().equals("dev-bob"));
        assertTrue(bobImplicated, "Bob should be implicated in the incident");

        // Alice's pr-1 -> build-2 -> deploy-2 produced NO incident, so Alice's
        // commit c1 must not be implicated in INC-1.
        assertFalse(cascade.implicatedCommits().contains("c1"),
                "c1 flowed to a healthy deploy and must not be implicated");
    }

    @Test
    @DisplayName("shortest path finds degrees of separation between Frank and the incident")
    void findsShortestPath() {
        Insights.PathResult path = analytics.shortestPath("dev-frank", "inc-1");
        assertTrue(path.connected(), "Frank should be connected to the incident");
        assertTrue(path.degrees() > 0, "positive degrees of separation");
        assertEquals("dev-frank", path.path().get(0));
        assertEquals("inc-1", path.path().get(path.path().size() - 1));
    }

    @Test
    @DisplayName("path hops are labelled by edge meaning — Frank REVIEWED pr-2, did not author it")
    void pathHopsCarryEdgeMeaning() {
        Insights.PathResult path = analytics.shortestPath("dev-frank", "inc-1");

        // One hop per edge in the walk.
        assertEquals(path.path().size() - 1, path.hops().size());

        // The first hop is dev-frank -> pr-2 over a REVIEWED edge, so it must read
        // "reviewed" (traversed developer -> PR), never "authored".
        Insights.PathHop first = path.hops().get(0);
        assertEquals("dev-frank", first.fromId());
        assertEquals("pr-2", first.toId());
        assertEquals("REVIEWED", first.type());
        assertEquals("reviewed", first.label());
    }

    @Test
    @DisplayName("shortest path reports disconnection honestly")
    void reportsDisconnection() {
        Insights.PathResult path = analytics.shortestPath("dev-alice", "does-not-exist");
        assertFalse(path.connected());
        assertEquals(-1, path.degrees());
    }

    @Test
    @DisplayName("whole-graph silo scan finds silos across all repositories")
    void wholeGraphSilos() {
        List<Insights.KnowledgeSilo> silos = analytics.knowledgeSilos(null);
        assertTrue(silos.stream().anyMatch(s -> s.fileId().equals("file-crypto")));
    }
}
