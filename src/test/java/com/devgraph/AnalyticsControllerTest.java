package com.devgraph;

import com.devgraph.graph.GraphStore;
import com.devgraph.graph.InMemoryGraphStore;
import com.devgraph.seed.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test: boots the full Spring context and exercises the HTTP endpoints
 * through MockMvc. This verifies the wiring, routing, JSON serialization, and
 * error handling that the pure-Java unit test in {@link ProductivityAnalyticsTest}
 * does not cover.
 *
 * <p>The graph store is deliberately overridden with a seeded {@link InMemoryGraphStore}
 * (see {@link TestGraphConfig}) so the web-layer test is hermetic: it does not
 * depend on a reachable Neo4j instance or on the state of a shared database.
 *
 * <p>{@code spring.main.lazy-initialization=true} is set for this test context only,
 * so the production {@code neo4jDriver} bean is never instantiated (nothing depends on
 * it once the {@code @Primary} in-memory store wins) and no outbound Neo4j connection
 * is attempted. Production startup remains eager, so real seeding still runs there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.main.lazy-initialization=true")
class AnalyticsControllerTest {

    @TestConfiguration
    static class TestGraphConfig {
        /** Takes precedence over the Neo4j-backed store, so no DB is needed. */
        @Bean
        @Primary
        public GraphStore testGraphStore() {
            GraphStore graph = new InMemoryGraphStore();
            SeedData.load(graph);
            return graph;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void healthOk() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void paymentsBusFactorIsOne() throws Exception {
        mvc.perform(get("/api/repositories/repo-payments/bus-factor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busFactor").value(1))
                .andExpect(jsonPath("$.topOwners[0].developerId").value("dev-dana"));
    }

    @Test
    void silosIncludeCrypto() throws Exception {
        mvc.perform(get("/api/repositories/all/silos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fileId=='file-crypto')]").exists());
    }

    @Test
    void blameCascadeImplicatesBob() throws Exception {
        mvc.perform(get("/api/incidents/inc-1/blame"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentTitle").value("Checkout 500s after deploy"))
                .andExpect(jsonPath("$.implicatedCommits[?(@=='c2')]").exists())
                .andExpect(jsonPath("$.implicatedDevelopers[0].developerId").value("dev-bob"));
    }

    @Test
    void unknownIncidentReturns404() throws Exception {
        mvc.perform(get("/api/incidents/does-not-exist/blame"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void pathFromFrankToIncidentIsConnected() throws Exception {
        mvc.perform(get("/api/path").param("from", "dev-frank").param("to", "inc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
    }
}
