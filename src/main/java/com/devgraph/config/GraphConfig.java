package com.devgraph.config;

import com.devgraph.domain.NodeType;
import com.devgraph.graph.GraphStore;
import com.devgraph.graph.Neo4jGraphStore;
import com.devgraph.seed.SeedData;
import com.devgraph.service.ProductivityAnalytics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;

/**
 * Spring wiring for the graph engine.
 *
 * <p>This is the DI equivalent of what the old hand-rolled {@code main()} did:
 * build a {@link GraphStore}, seed it, and expose a {@link ProductivityAnalytics}
 * over it. The important design property is preserved — the bean is typed as the
 * {@link GraphStore} interface, so swapping in a {@code Neo4jGraphStore} later is
 * a change to this one method and nothing else in the app.
 */
@Configuration
public class GraphConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.user}") String user,
            @Value("${neo4j.password}") String password) {
        // The driver is a long-lived, thread-safe connection pool. It must stay
        // open for the whole application lifetime, so we do NOT use
        // try-with-resources here (that would close it before returning). Spring
        // closes it on shutdown via destroyMethod = "close".
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        // Fail fast at startup if the database is unreachable: a graph-analytics
        // service with no graph cannot serve any request, so aborting boot with a
        // clear, actionable message beats an obscure "failed to create bean" trace
        // later when the store first touches the DB. (A DB that drops AFTER boot is
        // handled gracefully at request time — see ApiExceptionHandler's 503 mapping.)
        try {
            driver.verifyConnectivity();
        } catch (ServiceUnavailableException ex) {
            throw new IllegalStateException(
                    "Cannot reach Neo4j at " + uri + " (user '" + user + "'). "
                            + "Check NEO4J_URI/NEO4J_USER/NEO4J_PASSWORD and that the database is running. "
                            + "Cause: " + ex.getMessage(), ex);
        }
        System.out.println("Neo4J Connection established.");
        return driver;
    }

    /**
     * The graph store, seeded once at startup. Singleton bean shared by the app.
     *
     * <p>This bean is intentionally NOT {@code @Lazy}: it must be created eagerly at
     * startup so {@link SeedData#load} runs and the database is populated before the
     * first request. (Making it lazy previously meant the store — and therefore the
     * seeding — was never triggered at boot, leaving the graph empty.) The web-layer
     * test avoids opening a real Neo4j connection by overriding this bean with an
     * in-memory {@code @Primary} store, not by making this one lazy.
     */
    @Bean
    public GraphStore graphStore(Driver driver) {
        GraphStore graph = new Neo4jGraphStore(driver);
        // Seed when the graph holds no REPOSITORY nodes, rather than when the DB is
        // merely empty. Every seed run creates repositories, so their absence is a
        // reliable "not seeded yet" signal — and this still re-seeds a fresh DB while
        // ignoring unrelated nodes another app might have written.
        if (graph.nodesOfType(NodeType.REPOSITORY).isEmpty()) {
            SeedData.load(graph);
        }
        System.out.println("Graph store ready: " + graph.nodeCount()
                + " nodes, " + graph.edgeCount() + " edges.");
        return graph;
    }

    /** The analytics engine, injected with the store. */
    @Bean
    public ProductivityAnalytics productivityAnalytics(GraphStore graphStore) {
        return new ProductivityAnalytics(graphStore);
    }
}
