package com.devgraph.graph;

import com.devgraph.domain.Direction;
import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The graph persistence seam.
 *
 * <p>This interface is the single most important design decision in the project.
 * Every analytic in {@code ProductivityAnalytics} is written against these six
 * primitives — add a node, add an edge, fetch a node, list nodes by type, and
 * (crucially) list the edges incident to a node in a given direction. Nothing in
 * the service or REST layers knows how the graph is stored.
 *
 * <p>Because the entire query surface is "give me the neighbourhood of a node",
 * this interface maps cleanly onto a real graph database. A {@code Neo4jGraphStore}
 * would implement {@link #neighbors} with a single Cypher
 * {@code MATCH (n)-[r]->(m)} and the rest of the application would not change.
 * That is the payoff of modeling the domain as a graph rather than as tables: the
 * storage engine is a swappable detail, and the "in-memory, no DB" choice here is
 * a legitimate implementation of the same contract, not a different design.
 */
public interface GraphStore {

    Node addNode(Node node);

    Edge addEdge(Edge edge);

    Optional<Node> getNode(String id);

    /** All nodes carrying the given label. */
    Collection<Node> nodesOfType(NodeType type);

    /**
     * The edges incident to {@code nodeId}, optionally filtered by type.
     *
     * @param nodeId    the anchor node
     * @param direction OUT = edges where nodeId is the source, IN = edges where it
     *                  is the target, BOTH = either
     * @param types     if empty, all edge types; otherwise only these types
     */
    List<Edge> edges(String nodeId, Direction direction, EdgeType... types);

    /**
     * The neighbouring node ids reachable from {@code nodeId} across matching edges.
     * Convenience over {@link #edges} for the common "who is adjacent" traversal.
     */
    List<String> neighbors(String nodeId, Direction direction, EdgeType... types);

    int nodeCount();

    int edgeCount();
}
