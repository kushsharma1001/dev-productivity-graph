package com.devgraph.graph;

import java.util.List;

/**
 * An <em>optional</em> acceleration capability a {@link GraphStore} may also
 * implement. It is deliberately separate from the core six-primitive
 * {@link GraphStore} contract so the swap seam stays clean: analytics code first
 * asks "does my store support this?" and only uses the fast path if so, otherwise
 * it falls back to composing the generic primitives.
 *
 * <p>The point of the seam is that a backend which can answer a whole traversal in
 * one round-trip (a real graph database) should be allowed to, instead of being
 * forced through hundreds of tiny {@code neighbors()} calls (the N+1 pattern that
 * makes the remote-Neo4j path slow). The in-memory store does <em>not</em>
 * implement this — it has no network cost, so the generic fallback is already
 * instant for it.
 *
 * <p>Every implementation must answer with <b>parameterised, non-concatenated</b>
 * Cypher (or equivalent): the query text is a fixed literal and all values travel
 * as driver parameters.
 */
public interface GraphAnalyticsQueries {

    /**
     * Per-file, per-developer commit counts for a repository (or the whole graph
     * when {@code repositoryId} is {@code null}), computed in a single query.
     *
     * <p>One row per (file, developer) pair. A file with no commits still yields a
     * single row with a {@code null} developer, so callers can see files that exist
     * but have no authors.
     *
     * @param repositoryId the repository to scope to, or {@code null} for all files
     * @return flat ownership rows; grouping is left to the caller
     */
    List<FileOwnershipRow> fileOwnership(String repositoryId);

    /**
     * One (file, developer) ownership fact. {@code developerId}/{@code developerName}
     * are {@code null} for a file that has no commits, in which case
     * {@code commits} is {@code 0}.
     */
    record FileOwnershipRow(
            String repositoryId,
            String fileId,
            String filePath,
            String developerId,
            String developerName,
            int commits) {
    }

    // ------------------------------------------------------------------
    // Collaboration inputs (drives "Who works together" + "Teams")
    // ------------------------------------------------------------------

    /**
     * Every developer-to-item association needed to derive the collaboration graph,
     * fetched in a single query. The caller partitions rows by {@link #kind()} into
     * per-developer sets and does the pairwise intersection — exactly as the generic
     * fallback does, so results are identical.
     */
    List<DevRelationRow> developerRelations();

    /** Which relation a {@link DevRelationRow} expresses. */
    enum RelationKind {
        /** Developer authored a commit that touched this file id. */
        FILE_TOUCHED,
        /** Developer authored a commit that is part of this pull-request id. */
        AUTHORED_PR,
        /** Developer reviewed this pull-request id. */
        REVIEWED_PR
    }

    /** One (kind, developer, item) association. */
    record DevRelationRow(RelationKind kind, String developerId, String itemId) {
    }

    // ------------------------------------------------------------------
    // Blame cascade
    // ------------------------------------------------------------------

    /**
     * The full incident cascade in one query: every (deployment, build, commit,
     * file, developer) tuple reachable backwards from {@code incidentId}. Rows
     * fan out over files, so the caller de-duplicates. {@code fileId}/{@code
     * developerId} may be {@code null} for a commit with no files/author.
     */
    List<BlameRow> blameRows(String incidentId);

    /** One row of the blame cascade; see {@link #blameRows}. */
    record BlameRow(
            String deploymentId,
            String buildId,
            String commitId,
            String fileId,
            String developerId,
            String developerName) {
    }

    // ------------------------------------------------------------------
    // Shortest path (degrees of separation)
    // ------------------------------------------------------------------

    /**
     * The shortest undirected path between two node ids, computed in one query
     * (Neo4j's {@code shortestPath}). Returns {@code null} when the two nodes are
     * not connected. The edges are returned in graph-stored direction so the caller
     * can label each hop.
     *
     * @param fromId start node id
     * @param toId   end node id
     */
    PathData pathBetween(String fromId, String toId);

    /** Node ids in order plus the edges between them (stored direction). */
    record PathData(List<String> nodeIds, List<PathEdge> edges) {
    }

    /** One edge on a path, in the direction it is stored in the graph. */
    record PathEdge(String type, String fromId, String toId) {
    }
}
