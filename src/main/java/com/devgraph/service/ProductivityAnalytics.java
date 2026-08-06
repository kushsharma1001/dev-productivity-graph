package com.devgraph.service;

import com.devgraph.domain.Direction;
import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;
import com.devgraph.graph.GraphAnalyticsQueries;
import com.devgraph.graph.GraphStore;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The analytic engine. Every public method answers a question about
 * <em>connections</em> — knowledge concentration, collaboration structure, and
 * blame propagation — by walking the graph. None of them is a single-table
 * lookup; each is a bounded traversal, a neighbourhood intersection, a shortest
 * path, or a fixpoint over the graph structure.
 *
 * <p>The engine depends only on {@link GraphStore}, so it runs unchanged on the
 * in-memory store or on a future Neo4j-backed store.
 */
public final class ProductivityAnalytics {

    private final GraphStore graph;

    public ProductivityAnalytics(GraphStore graph) {
        this.graph = graph;
    }

    /**
     * Per-file ownership for a scope, resolved <em>once</em>.
     *
     * <p>If the store advertises {@link GraphAnalyticsQueries}, the whole scope is
     * fetched in a single parameterised query and grouped in memory — this replaces
     * the per-file, per-commit N+1 storm that made the remote-Neo4j path slow. Any
     * store that does not (the in-memory one) transparently falls back to composing
     * the generic {@link GraphStore} primitives, which is already instant in RAM.
     * Both paths produce identical {@link FileOwnershipView} values, so every
     * downstream analytic is agnostic to which was used.
     */
    private List<FileOwnershipView> fileOwnershipViews(String repositoryId) {
        if (graph instanceof GraphAnalyticsQueries fast) {
            return groupOwnershipRows(fast.fileOwnership(repositoryId));
        }
        List<FileOwnershipView> views = new ArrayList<>();
        for (Node file : filesInScope(repositoryId)) {
            Map<String, Integer> commitsByDev = commitsPerDeveloperForFile(file.id());
            Map<String, String> names = new HashMap<>();
            for (String devId : commitsByDev.keySet()) {
                names.put(devId, nameOf(devId));
            }
            views.add(new FileOwnershipView(
                    file.id(), file.str("path"), repoOf(file.id()), commitsByDev, names));
        }
        return views;
    }

    /** Group flat (file, developer) rows from the fast path into per-file views. */
    private static List<FileOwnershipView> groupOwnershipRows(
            List<GraphAnalyticsQueries.FileOwnershipRow> rows) {
        Map<String, FileOwnershipView> byFile = new LinkedHashMap<>();
        for (GraphAnalyticsQueries.FileOwnershipRow r : rows) {
            FileOwnershipView v = byFile.computeIfAbsent(r.fileId(), k ->
                    new FileOwnershipView(r.fileId(), r.filePath(), r.repositoryId(),
                            new LinkedHashMap<>(), new HashMap<>()));
            // A file with no commits comes back as a single null-developer row; it
            // still creates the (empty) view above but contributes no author.
            if (r.developerId() != null && r.commits() > 0) {
                v.commitsByDev().merge(r.developerId(), r.commits(), Integer::sum);
                v.devNames().put(r.developerId(), r.developerName());
            }
        }
        return new ArrayList<>(byFile.values());
    }

    /** A file plus its per-developer commit counts and developer display names. */
    private record FileOwnershipView(
            String fileId,
            String filePath,
            String repositoryId,
            Map<String, Integer> commitsByDev,
            Map<String, String> devNames) {
    }

    // ------------------------------------------------------------------
    // BUS FACTOR
    // ------------------------------------------------------------------

    /**
     * Bus factor of a repository.
     *
     * <p>For each file we determine its <em>dominant author</em> — the developer
     * with the most commits touching it. We then ask: what is the smallest set of
     * developers whose dominated files together exceed 50% of the repo? That count
     * is the bus factor. A value of 1 or 2 on a large repo is a red flag: the
     * majority of the codebase's knowledge sits with one or two people.
     *
     * <p>Traversal: {@code Repository <-IN_REPO- File <-TOUCHES- Commit <-AUTHORED- Developer}.
     */
    public Insights.BusFactor busFactor(String repositoryId) {
        List<FileOwnershipView> files = fileOwnershipViews(repositoryId);

        // developerId -> number of files they dominate
        Map<String, Integer> filesOwned = new HashMap<>();
        Map<String, String> ownerNames = new HashMap<>();
        int soleOwned = 0;

        for (FileOwnershipView file : files) {
            Map<String, Integer> commitsByDev = file.commitsByDev();
            if (commitsByDev.isEmpty()) {
                continue;
            }
            if (commitsByDev.size() == 1) {
                soleOwned++;
            }
            String dominant = commitsByDev.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            filesOwned.merge(dominant, 1, Integer::sum);
            ownerNames.putIfAbsent(dominant, nameOfIn(file, dominant));
        }

        int fileCount = files.size();

        // Greedy: sort owners by files dominated (desc), accumulate to majority.
        List<Insights.OwnerShare> owners = new ArrayList<>();
        filesOwned.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .forEach(e -> owners.add(new Insights.OwnerShare(
                        e.getKey(),
                        ownerNames.get(e.getKey()),
                        e.getValue(),
                        fileCount == 0 ? 0.0 : round(100.0 * e.getValue() / fileCount))));

        int busFactor = 0;
        int cumulative = 0;
        for (Insights.OwnerShare o : owners) {
            busFactor++;
            cumulative += o.filesOwned();
            if (cumulative * 2 > fileCount) { // strictly more than half
                break;
            }
        }

        return new Insights.BusFactor(repositoryId, fileCount, busFactor, owners, soleOwned);
    }

    // ------------------------------------------------------------------
    // KNOWLEDGE SILOS
    // ------------------------------------------------------------------

    /**
     * Files known to exactly one developer, across a repository (or the whole
     * graph if {@code repositoryId} is null). These are the true single points of
     * failure — code with a bus factor of one.
     *
     * <p>Traversal per file: {@code File <-TOUCHES- Commit <-AUTHORED- Developer};
     * a silo is a file whose author set has size 1.
     */
    public List<Insights.KnowledgeSilo> knowledgeSilos(String repositoryId) {
        List<FileOwnershipView> files = fileOwnershipViews(repositoryId);
        List<Insights.KnowledgeSilo> silos = new ArrayList<>();

        for (FileOwnershipView file : files) {
            Map<String, Integer> commitsByDev = file.commitsByDev();
            if (commitsByDev.size() == 1) {
                Map.Entry<String, Integer> sole = commitsByDev.entrySet().iterator().next();
                silos.add(new Insights.KnowledgeSilo(
                        file.fileId(),
                        file.filePath(),
                        file.repositoryId(),
                        sole.getKey(),
                        nameOfIn(file, sole.getKey()),
                        sole.getValue()));
            }
        }
        silos.sort(Comparator.comparingInt(Insights.KnowledgeSilo::commitCount).reversed());
        return silos;
    }

    /**
     * File-level ownership for a repository — the drill-down behind the
     * bus-factor summary counts. For each file it lists every developer who has
     * touched it (with commit counts), its dominant owner, and whether it is
     * sole-owned. This is what lets the UI expand "3 files" into their names.
     *
     * <p>Traversal per file: {@code File <-TOUCHES- Commit <-AUTHORED- Developer}.
     */
    public Insights.RepositoryFiles repositoryFiles(String repositoryId) {
        Node repo = graph.getNode(repositoryId).orElseThrow(
                () -> new IllegalArgumentException("No such repository: " + repositoryId));

        List<Insights.FileOwnership> files = new ArrayList<>();
        for (FileOwnershipView file : fileOwnershipViews(repositoryId)) {
            List<Insights.FileAuthor> authors = new ArrayList<>();
            file.commitsByDev().entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                    .forEach(e -> authors.add(new Insights.FileAuthor(
                            e.getKey(), nameOfIn(file, e.getKey()), e.getValue())));

            String dominantId = authors.isEmpty() ? null : authors.get(0).developerId();
            files.add(new Insights.FileOwnership(
                    file.fileId(),
                    file.filePath(),
                    dominantId,
                    dominantId == null ? null : authors.get(0).developerName(),
                    authors.size(),
                    authors.size() == 1,
                    authors));
        }
        files.sort(Comparator.comparing(Insights.FileOwnership::filePath,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new Insights.RepositoryFiles(repositoryId, repo.str("name"), files);
    }

    // ------------------------------------------------------------------
    // COLLABORATION
    // ------------------------------------------------------------------

    /**
     * Derives the collaboration graph among developers. Two developers collaborate
     * if one reviewed the other's pull requests and/or they co-edited files. The
     * relationship is <em>computed</em> from review and authorship edges — it is
     * never stored, which is exactly the point: the collaboration network is an
     * emergent property of the delivery graph.
     *
     * @param minStrength only return pairs at or above this strength
     */
    public List<Insights.Collaboration> collaborations(double minStrength) {
        // Precompute each developer's file set, authored-PR set and reviewed-PR set.
        // Fast path: one query fetches every association and we bucket in memory.
        // Fallback: compose the generic primitives (instant in RAM).
        Map<String, Set<String>> filesByDev = new HashMap<>();
        Map<String, Set<String>> authoredPrsByDev = new HashMap<>();
        Map<String, Set<String>> reviewedPrsByDev = new HashMap<>();

        List<String> devIds = new ArrayList<>();
        for (Node dev : graph.nodesOfType(NodeType.DEVELOPER)) {
            devIds.add(dev.id());
            filesByDev.put(dev.id(), new HashSet<>());
            authoredPrsByDev.put(dev.id(), new HashSet<>());
            reviewedPrsByDev.put(dev.id(), new HashSet<>());
        }

        if (graph instanceof GraphAnalyticsQueries fast) {
            for (GraphAnalyticsQueries.DevRelationRow r : fast.developerRelations()) {
                Map<String, Set<String>> bucket = switch (r.kind()) {
                    case FILE_TOUCHED -> filesByDev;
                    case AUTHORED_PR  -> authoredPrsByDev;
                    case REVIEWED_PR  -> reviewedPrsByDev;
                };
                // Ignore rows for any id that is not a known developer node.
                Set<String> set = bucket.get(r.developerId());
                if (set != null) {
                    set.add(r.itemId());
                }
            }
        } else {
            for (String devId : devIds) {
                filesByDev.put(devId, filesTouchedBy(devId));
                authoredPrsByDev.put(devId, authoredPrs(devId));
                reviewedPrsByDev.put(devId, new HashSet<>(
                        graph.neighbors(devId, Direction.OUT, EdgeType.REVIEWED)));
            }
        }

        List<String> devList = devIds;
        List<Insights.Collaboration> result = new ArrayList<>();

        for (int i = 0; i < devList.size(); i++) {
            for (int j = i + 1; j < devList.size(); j++) {
                String a = devList.get(i);
                String b = devList.get(j);

                // shared reviews: a reviewed b's PRs, plus b reviewed a's PRs
                int sharedReviews =
                        intersectionSize(reviewedPrsByDev.get(a), authoredPrsByDev.get(b))
                                + intersectionSize(reviewedPrsByDev.get(b), authoredPrsByDev.get(a));

                int coEdited = intersectionSize(filesByDev.get(a), filesByDev.get(b));

                // Reviews are direct human interaction, weighted higher than
                // incidental co-editing of the same file.
                double strength = sharedReviews * 2.0 + coEdited;

                if (strength >= minStrength && strength > 0) {
                    result.add(new Insights.Collaboration(a, b, sharedReviews, coEdited, round(strength)));
                }
            }
        }
        result.sort(Comparator.comparingDouble(Insights.Collaboration::strength).reversed());
        return result;
    }

    /**
     * Connected components of the collaboration graph (above {@code minStrength}).
     * Each component is a de-facto sub-team. Union-find over the derived
     * collaboration edges.
     */
    public List<Insights.CollaborationCluster> collaborationClusters(double minStrength) {
        List<Insights.Collaboration> edges = collaborations(minStrength);

        Map<String, String> parent = new HashMap<>();
        for (Node dev : graph.nodesOfType(NodeType.DEVELOPER)) {
            parent.put(dev.id(), dev.id());
        }
        Map<String, Integer> internalEdges = new HashMap<>();

        for (Insights.Collaboration c : edges) {
            union(parent, c.developerA(), c.developerB());
        }
        // Count edges per component root.
        for (Insights.Collaboration c : edges) {
            String root = find(parent, c.developerA());
            internalEdges.merge(root, 1, Integer::sum);
        }

        Map<String, List<String>> byRoot = new HashMap<>();
        for (String dev : parent.keySet()) {
            byRoot.computeIfAbsent(find(parent, dev), k -> new ArrayList<>()).add(dev);
        }

        List<Insights.CollaborationCluster> clusters = new ArrayList<>();
        int id = 0;
        for (Map.Entry<String, List<String>> e : byRoot.entrySet()) {
            if (e.getValue().size() < 2) {
                continue; // singletons aren't clusters
            }
            List<String> members = new ArrayList<>(e.getValue());
            members.sort(Comparator.naturalOrder());
            clusters.add(new Insights.CollaborationCluster(
                    id++, members, internalEdges.getOrDefault(e.getKey(), 0)));
        }
        clusters.sort(Comparator.comparingInt((Insights.CollaborationCluster c) -> c.developerIds().size()).reversed());
        return clusters;
    }

    // ------------------------------------------------------------------
    // BLAME CASCADE  (the query that is irreducibly a graph traversal)
    // ------------------------------------------------------------------

    /**
     * Walk backwards from an incident to everyone and everything implicated.
     *
     * <p>{@code Incident <-CAUSED- Deployment <-PRODUCED- Build <-TRIGGERED-
     * PullRequest <-PART_OF- Commit -TOUCHES-> File}, and {@code Commit <-AUTHORED-
     * Developer}. This is a variable-length reverse path with a fan-out at the
     * commit level; expressing it in SQL requires several recursive CTEs, whereas
     * here it is a straightforward multi-step BFS over typed edges.
     */
    public Insights.BlameCascade blameCascade(String incidentId) {
        Node incident = graph.getNode(incidentId).orElseThrow(
                () -> new IllegalArgumentException("No such incident: " + incidentId));

        List<String> deploymentPath = new ArrayList<>();
        Set<String> commits = new LinkedHashSet<>();
        Set<String> files = new LinkedHashSet<>();
        Map<String, Integer> commitsByDev = new HashMap<>();
        Map<String, String> devNames = new HashMap<>();

        if (graph instanceof GraphAnalyticsQueries fast) {
            // One query returns every (deployment, build, commit, file, developer)
            // tuple; we de-duplicate here. Rows fan out over files, so a commit's
            // author is counted once per commit, not once per row.
            Set<String> seenDeployments = new LinkedHashSet<>();
            Set<String> seenBuilds = new LinkedHashSet<>();
            Set<String> countedCommitDev = new HashSet<>();
            for (GraphAnalyticsQueries.BlameRow r : fast.blameRows(incidentId)) {
                if (r.deploymentId() != null) {
                    seenDeployments.add(r.deploymentId());
                }
                if (r.buildId() != null) {
                    seenBuilds.add(r.buildId());
                }
                if (r.commitId() != null) {
                    commits.add(r.commitId());
                }
                if (r.fileId() != null) {
                    files.add(r.fileId());
                }
                if (r.commitId() != null && r.developerId() != null
                        && countedCommitDev.add(r.commitId() + '\0' + r.developerId())) {
                    commitsByDev.merge(r.developerId(), 1, Integer::sum);
                    if (r.developerName() != null) {
                        devNames.put(r.developerId(), r.developerName());
                    }
                }
            }
            // Preserve the deployment-then-build ordering of the walked path.
            deploymentPath.addAll(seenDeployments);
            deploymentPath.addAll(seenBuilds);
        } else {
            // Incident <- Deployment
            for (String deploymentId : graph.neighbors(incidentId, Direction.IN, EdgeType.CAUSED)) {
                deploymentPath.add(deploymentId);
                // Deployment <- Build
                for (String buildId : graph.neighbors(deploymentId, Direction.IN, EdgeType.PRODUCED)) {
                    deploymentPath.add(buildId);
                    // Build <- PullRequest (and also directly <- Commit if a commit triggered it)
                    List<String> triggerers = graph.neighbors(buildId, Direction.IN, EdgeType.TRIGGERED);
                    for (String triggerId : triggerers) {
                        Optional<Node> tNode = graph.getNode(triggerId);
                        if (tNode.isEmpty()) {
                            continue;
                        }
                        if (tNode.get().type() == NodeType.PULL_REQUEST) {
                            // PR <- Commit
                            for (String commitId : graph.neighbors(triggerId, Direction.IN, EdgeType.PART_OF)) {
                                addCommit(commitId, commits, files, commitsByDev);
                            }
                        } else if (tNode.get().type() == NodeType.COMMIT) {
                            addCommit(triggerId, commits, files, commitsByDev);
                        }
                    }
                }
            }
        }

        List<Insights.ImplicatedDeveloper> devs = new ArrayList<>();
        commitsByDev.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .forEach(e -> devs.add(new Insights.ImplicatedDeveloper(
                        e.getKey(),
                        // Use the name fetched inline on the fast path; fall back to a
                        // lookup only when it is absent (the generic path never fills it).
                        devNames.getOrDefault(e.getKey(), nameOf(e.getKey())),
                        e.getValue())));

        return new Insights.BlameCascade(
                incidentId,
                incident.str("title"),
                deploymentPath,
                new ArrayList<>(commits),
                new ArrayList<>(files),
                devs);
    }

    // ------------------------------------------------------------------
    // SHORTEST PATH  (degrees of separation)
    // ------------------------------------------------------------------

    /**
     * Undirected shortest path between any two nodes via BFS. Useful for
     * "how is this developer connected to that incident" or "degrees of
     * separation between two developers through shared code/reviews".
     */
    public Insights.PathResult shortestPath(String fromId, String toId) {
        if (fromId.equals(toId) && graph.getNode(fromId).isPresent()) {
            return new Insights.PathResult(fromId, toId, 0, List.of(fromId), List.of(), true);
        }

        // Fast path: let the database compute the shortest path in one round-trip,
        // instead of a BFS that costs one round-trip per visited node.
        if (graph instanceof GraphAnalyticsQueries fast) {
            GraphAnalyticsQueries.PathData data = fast.pathBetween(fromId, toId);
            if (data == null || data.nodeIds().isEmpty()) {
                return new Insights.PathResult(fromId, toId, -1, List.of(), List.of(), false);
            }
            List<String> path = data.nodeIds();
            List<Insights.PathHop> hops = buildHopsFromEdges(path, data.edges());
            return new Insights.PathResult(fromId, toId, path.size() - 1, path, hops, true);
        }

        if (graph.getNode(fromId).isEmpty() || graph.getNode(toId).isEmpty()) {
            return new Insights.PathResult(fromId, toId, -1, List.of(), List.of(), false);
        }

        Map<String, String> predecessor = new HashMap<>();
        // The edge each node was discovered through, so we can report HOW two nodes
        // are connected (e.g. "reviewed" vs "authored"), not just THAT they are.
        Map<String, Edge> predecessorEdge = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromId);
        visited.add(fromId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            // Walk edges (not bare neighbours) so the edge type + direction survive.
            for (Edge e : graph.edges(current, Direction.BOTH)) {
                String next = e.other(current);
                if (visited.add(next)) {
                    predecessor.put(next, current);
                    predecessorEdge.put(next, e);
                    if (next.equals(toId)) {
                        List<String> path = reconstruct(predecessor, fromId, toId);
                        List<Insights.PathHop> hops = buildHops(path, predecessorEdge);
                        return new Insights.PathResult(
                                fromId, toId, path.size() - 1, path, hops, true);
                    }
                    queue.add(next);
                }
            }
        }
        return new Insights.PathResult(fromId, toId, -1, List.of(), List.of(), false);
    }

    /**
     * Turn the node path into labelled hops. For each consecutive pair we look up
     * the edge it was discovered through and describe it in the walked direction:
     * a REVIEWED edge traversed developer -> PR reads "reviewed", the reverse reads
     * "was reviewed by".
     */
    private List<Insights.PathHop> buildHops(List<String> path, Map<String, Edge> predecessorEdge) {
        List<Insights.PathHop> hops = new ArrayList<>();
        for (int i = 0; i + 1 < path.size(); i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            Edge e = predecessorEdge.get(b); // edge that discovered b (from a)
            boolean forward = e != null && a.equals(e.from()) && b.equals(e.to());
            String type = e == null ? "RELATED" : e.type().name();
            hops.add(new Insights.PathHop(a, b, type, hopLabel(e == null ? null : e.type(), forward)));
        }
        return hops;
    }

    /**
     * Label hops for a path returned by the fast query. Each {@link
     * GraphAnalyticsQueries.PathEdge} carries the relationship's stored endpoints,
     * so we compare them to the walked direction to pick the right verb — the same
     * logic as {@link #buildHops}, but sourced from the query result rather than the
     * BFS predecessor map.
     */
    private List<Insights.PathHop> buildHopsFromEdges(
            List<String> path, List<GraphAnalyticsQueries.PathEdge> edges) {
        List<Insights.PathHop> hops = new ArrayList<>();
        for (int i = 0; i + 1 < path.size() && i < edges.size(); i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            GraphAnalyticsQueries.PathEdge e = edges.get(i);
            EdgeType type = parseEdgeType(e.type());
            boolean forward = a.equals(e.fromId()) && b.equals(e.toId());
            hops.add(new Insights.PathHop(
                    a, b, type == null ? "RELATED" : type.name(), hopLabel(type, forward)));
        }
        return hops;
    }

    private static EdgeType parseEdgeType(String name) {
        try {
            return name == null ? null : EdgeType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null; // unknown relationship type -> generic "is related to"
        }
    }

    /** Human verb for an edge traversed in the given direction (from -> to). */
    private static String hopLabel(EdgeType type, boolean forward) {
        if (type == null) {
            return "is related to";
        }
        return switch (type) {
            case AUTHORED     -> forward ? "authored" : "was authored by";
            case TOUCHES      -> forward ? "changed" : "was changed by";
            case IN_REPO      -> forward ? "is in" : "contains";
            case PART_OF      -> forward ? "is part of" : "includes";
            case REVIEWED     -> forward ? "reviewed" : "was reviewed by";
            case TRIGGERED    -> forward ? "triggered" : "was triggered by";
            case PRODUCED     -> forward ? "produced" : "was produced by";
            case CAUSED       -> forward ? "caused" : "was caused by";
            case FAILED_TEST  -> forward ? "failed a test in" : "had a failing test from";
            case IMPLICATED_IN -> forward ? "is implicated in" : "implicates";
        };
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void addCommit(String commitId, Set<String> commits, Set<String> files,
                           Map<String, Integer> commitsByDev) {
        commits.add(commitId);
        for (String fileId : graph.neighbors(commitId, Direction.OUT, EdgeType.TOUCHES)) {
            files.add(fileId);
        }
        for (String devId : graph.neighbors(commitId, Direction.IN, EdgeType.AUTHORED)) {
            commitsByDev.merge(devId, 1, Integer::sum);
        }
    }

    /** For a file: developerId -> number of commits by that developer touching it. */
    private Map<String, Integer> commitsPerDeveloperForFile(String fileId) {
        Map<String, Integer> commitsByDev = new HashMap<>();
        for (String commitId : graph.neighbors(fileId, Direction.IN, EdgeType.TOUCHES)) {
            for (String devId : graph.neighbors(commitId, Direction.IN, EdgeType.AUTHORED)) {
                commitsByDev.merge(devId, 1, Integer::sum);
            }
        }
        return commitsByDev;
    }

    private Set<String> filesTouchedBy(String devId) {
        Set<String> files = new HashSet<>();
        for (String commitId : graph.neighbors(devId, Direction.OUT, EdgeType.AUTHORED)) {
            files.addAll(graph.neighbors(commitId, Direction.OUT, EdgeType.TOUCHES));
        }
        return files;
    }

    private Set<String> authoredPrs(String devId) {
        Set<String> prs = new HashSet<>();
        for (String commitId : graph.neighbors(devId, Direction.OUT, EdgeType.AUTHORED)) {
            prs.addAll(graph.neighbors(commitId, Direction.OUT, EdgeType.PART_OF));
        }
        return prs;
    }

    private Collection<Node> filesInScope(String repositoryId) {
        if (repositoryId == null) {
            return graph.nodesOfType(NodeType.FILE);
        }
        List<Node> files = new ArrayList<>();
        for (String fileId : graph.neighbors(repositoryId, Direction.IN, EdgeType.IN_REPO)) {
            graph.getNode(fileId).ifPresent(files::add);
        }
        return files;
    }

    private String repoOf(String fileId) {
        List<String> repos = graph.neighbors(fileId, Direction.OUT, EdgeType.IN_REPO);
        return repos.isEmpty() ? null : repos.get(0);
    }

    private String nameOf(String devId) {
        return graph.getNode(devId).map(n -> n.str("name")).orElse(devId);
    }

    /**
     * Developer display name resolved from the view's inline name map (populated by
     * both the fast query and the fallback), so it never triggers an extra lookup.
     * Falls back to a store read only if a name is somehow missing.
     */
    private String nameOfIn(FileOwnershipView file, String devId) {
        String name = file.devNames().get(devId);
        return name != null ? name : nameOf(devId);
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        if (a == null || b == null) {
            return 0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int count = 0;
        for (String s : smaller) {
            if (larger.contains(s)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> reconstruct(Map<String, String> predecessor, String from, String to) {
        Deque<String> stack = new ArrayDeque<>();
        String cur = to;
        while (cur != null && !cur.equals(from)) {
            stack.push(cur);
            cur = predecessor.get(cur);
        }
        stack.push(from);
        return new ArrayList<>(stack);
    }

    // --- union-find ---
    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // path compression
        String cur = x;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
