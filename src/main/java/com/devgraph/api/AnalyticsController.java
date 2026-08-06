package com.devgraph.api;

import com.devgraph.service.Insights;
import com.devgraph.service.ProductivityAnalytics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for the productivity graph.
 *
 * <p>Each endpoint delegates to {@link ProductivityAnalytics} and returns a
 * record from {@link Insights}; Spring MVC + Jackson serialize it to JSON. This
 * is the idiomatic Spring replacement for the old hand-rolled HttpServer — adding
 * a new API is now just another {@code @GetMapping}/{@code @PostMapping} method.
 *
 * <p>Routes (unchanged from the original app):
 * <pre>
 *   GET /api/repositories/{repoId}/bus-factor
 *   GET /api/repositories/{repoId}/silos        (repoId "all" = whole graph)
 *   GET /api/collaborations?minStrength=1
 *   GET /api/clusters?minStrength=1
 *   GET /api/incidents/{incidentId}/blame
 *   GET /api/path?from={id}&to={id}
 *   GET /api/health
 * </pre>
 */
@RestController
public class AnalyticsController {

    private final ProductivityAnalytics analytics;

    public AnalyticsController(ProductivityAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/health")
    public java.util.Map<String, String> health() {
        return java.util.Map.of("status", "ok");
    }

    @GetMapping("/api/repositories/{repoId}/bus-factor")
    public Insights.BusFactor busFactor(@PathVariable String repoId) {
        return analytics.busFactor(repoId);
    }

    @GetMapping("/api/repositories/{repoId}/silos")
    public List<Insights.KnowledgeSilo> silos(@PathVariable String repoId) {
        // "all" is a sentinel meaning "scan every repository".
        return analytics.knowledgeSilos("all".equals(repoId) ? null : repoId);
    }

    @GetMapping("/api/repositories/{repoId}/files")
    public Insights.RepositoryFiles files(@PathVariable String repoId) {
        return analytics.repositoryFiles(repoId);
    }

    @GetMapping("/api/collaborations")
    public List<Insights.Collaboration> collaborations(
            @RequestParam(defaultValue = "1.0") double minStrength) {
        return analytics.collaborations(minStrength);
    }

    @GetMapping("/api/clusters")
    public List<Insights.CollaborationCluster> clusters(
            @RequestParam(defaultValue = "1.0") double minStrength) {
        return analytics.collaborationClusters(minStrength);
    }

    @GetMapping("/api/incidents/{incidentId}/blame")
    public Insights.BlameCascade blame(@PathVariable String incidentId) {
        return analytics.blameCascade(incidentId);
    }

    @GetMapping("/api/path")
    public Insights.PathResult path(@RequestParam String from, @RequestParam String to) {
        return analytics.shortestPath(from, to);
    }
}
