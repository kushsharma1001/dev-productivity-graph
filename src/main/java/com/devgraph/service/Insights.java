package com.devgraph.service;

import java.util.List;

/**
 * Immutable result types returned by {@link ProductivityAnalytics}.
 *
 * <p>These are plain records so Jackson can serialize them to JSON directly and
 * so tests can assert on them without mocking. Each one is the answer to a
 * question that is a graph traversal, not a table scan.
 */
public final class Insights {

    private Insights() {
    }

    /**
     * Bus factor for a single repository: how concentrated is knowledge of its
     * files among developers. A low bus factor with high concentration is the
     * classic "if this one person leaves, we're in trouble" signal.
     *
     * @param repositoryId       the repo analysed
     * @param fileCount          number of files in the repo
     * @param busFactor          minimum number of developers who, together, own a
     *                           majority of the files (see algorithm for definition)
     * @param topOwners          developers ranked by number of files they are the
     *                           dominant author of
     * @param soleOwnedFileCount files touched by exactly one developer (pure silos)
     */
    public record BusFactor(
            String repositoryId,
            int fileCount,
            int busFactor,
            List<OwnerShare> topOwners,
            int soleOwnedFileCount) {
    }

    /** A developer's share of ownership within a repository. */
    public record OwnerShare(String developerId, String developerName, int filesOwned, double ownershipPct) {
    }

    /**
     * A knowledge silo: a file (or set of files) known to only one developer.
     * The risk is that this knowledge has a "bus factor of one".
     */
    public record KnowledgeSilo(
            String fileId,
            String filePath,
            String repositoryId,
            String soleOwnerId,
            String soleOwnerName,
            int commitCount) {
    }

    /**
     * A collaboration edge derived from the graph: two developers who have
     * reviewed each other's pull requests and/or co-edited the same files.
     * This is a projected/derived relationship — it is not stored, it is computed
     * from the underlying review and authorship structure.
     */
    public record Collaboration(
            String developerA,
            String developerB,
            int sharedReviews,
            int coEditedFiles,
            double strength) {
    }

    /**
     * A cluster of developers who collaborate tightly (a connected component of
     * the collaboration graph above a strength threshold). Sub-teams that never
     * appear on an org chart often show up cleanly here.
     */
    public record CollaborationCluster(int clusterId, List<String> developerIds, int internalEdges) {
    }

    /**
     * The result of a blame cascade: starting from an incident, walk backwards
     * through deployment -> build -> pull request / commit -> file -> developer
     * to find every person and artifact implicated. This is the query that is
     * impossible to phrase as a single relational row lookup — it is a
     * variable-length reverse path.
     */
    public record BlameCascade(
            String incidentId,
            String incidentTitle,
            List<String> deploymentPath,
            List<String> implicatedCommits,
            List<String> implicatedFiles,
            List<ImplicatedDeveloper> implicatedDevelopers) {
    }

    /** A developer implicated in an incident, with how many implicated commits they authored. */
    public record ImplicatedDeveloper(String developerId, String developerName, int implicatedCommits) {
    }

    /**
     * Degrees of separation / shortest path between two nodes.
     *
     * <p>{@code path} is the ordered list of node ids (kept for backwards
     * compatibility); {@code hops} is the same walk expressed as edges, so the UI
     * can say <em>how</em> each pair is connected ("reviewed", "authored", …)
     * rather than just drawing an anonymous arrow.
     */
    public record PathResult(
            String from,
            String to,
            int degrees,
            List<String> path,
            List<PathHop> hops,
            boolean connected) {
    }

    /**
     * One edge in a path, described in the direction the path is walked (from ->
     * to). {@code type} is the raw {@link com.devgraph.domain.EdgeType} name and
     * {@code label} is a human verb phrase for that edge as traversed — e.g. a
     * developer -> PR hop over a REVIEWED edge is labelled "reviewed", while the
     * reverse traversal is "was reviewed by".
     */
    public record PathHop(String fromId, String toId, String type, String label) {
    }

    /**
     * File-level ownership detail for a repository — the drill-down behind the
     * bus-factor summary counts. Lets the UI turn "3 files" into the actual list
     * of file paths and who owns each.
     */
    public record RepositoryFiles(String repositoryId, String repositoryName, List<FileOwnership> files) {
    }

    /**
     * One file's ownership: its dominant author, whether it is sole-owned (a
     * silo), and the full per-developer commit breakdown.
     */
    public record FileOwnership(
            String fileId,
            String filePath,
            String dominantOwnerId,
            String dominantOwnerName,
            int authorCount,
            boolean soleOwned,
            List<FileAuthor> authors) {
    }

    /** A developer who has touched a file, with how many commits they made to it. */
    public record FileAuthor(String developerId, String developerName, int commits) {
    }
}
