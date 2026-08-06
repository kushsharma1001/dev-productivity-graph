package com.devgraph.graph;

import com.devgraph.domain.Direction;
import com.devgraph.domain.Edge;
import com.devgraph.domain.EdgeType;
import com.devgraph.domain.Node;
import com.devgraph.domain.NodeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory adjacency-list graph store.
 *
 * <p>Design notes that make this a faithful graph engine rather than a toy:
 * <ul>
 *   <li><b>Dual adjacency indexes.</b> We maintain both an out-adjacency and an
 *       in-adjacency list per node. A relational model would force you to scan the
 *       whole edge set to answer "who points at me"; a graph store answers both
 *       directions in O(degree). This is exactly why the interesting queries here
 *       (bus factor, blame cascade) are cheap — they are local neighbourhood walks.</li>
 *   <li><b>Concurrency.</b> Backing maps are {@link ConcurrentHashMap} and the
 *       per-node edge lists are synchronized on write, so the REST server can read
 *       the graph from many request threads safely. The seed graph is effectively
 *       read-only after startup, but we don't rely on that.</li>
 * </ul>
 */
public final class InMemoryGraphStore implements GraphStore {

    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, List<Edge>> outEdges = new ConcurrentHashMap<>();
    private final Map<String, List<Edge>> inEdges = new ConcurrentHashMap<>();
    private int edgeCount = 0;

    @Override
    public Node addNode(Node node) {
        nodes.put(node.id(), node);
        outEdges.computeIfAbsent(node.id(), k -> new ArrayList<>());
        inEdges.computeIfAbsent(node.id(), k -> new ArrayList<>());
        return node;
    }

    @Override
    public synchronized Edge addEdge(Edge edge) {
        if (!nodes.containsKey(edge.from())) {
            throw new IllegalArgumentException("Unknown source node: " + edge.from());
        }
        if (!nodes.containsKey(edge.to())) {
            throw new IllegalArgumentException("Unknown target node: " + edge.to());
        }
        outEdges.get(edge.from()).add(edge);
        inEdges.get(edge.to()).add(edge);
        edgeCount++;
        return edge;
    }

    @Override
    public Optional<Node> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Collection<Node> nodesOfType(NodeType type) {
        List<Node> result = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.type() == type) {
                result.add(n);
            }
        }
        return result;
    }

    @Override
    public List<Edge> edges(String nodeId, Direction direction, EdgeType... types) {
        List<Edge> result = new ArrayList<>();
        if (direction == Direction.OUT || direction == Direction.BOTH) {
            collect(outEdges.get(nodeId), result, types);
        }
        if (direction == Direction.IN || direction == Direction.BOTH) {
            collect(inEdges.get(nodeId), result, types);
        }
        return result;
    }

    private void collect(List<Edge> source, List<Edge> sink, EdgeType... types) {
        if (source == null) {
            return;
        }
        for (Edge e : source) {
            if (matches(e.type(), types)) {
                sink.add(e);
            }
        }
    }

    private boolean matches(EdgeType type, EdgeType... types) {
        if (types == null || types.length == 0) {
            return true;
        }
        for (EdgeType t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> neighbors(String nodeId, Direction direction, EdgeType... types) {
        List<String> result = new ArrayList<>();
        for (Edge e : edges(nodeId, direction, types)) {
            result.add(e.other(nodeId));
        }
        return result;
    }

    @Override
    public int nodeCount() {
        return nodes.size();
    }

    @Override
    public int edgeCount() {
        return edgeCount;
    }
}
