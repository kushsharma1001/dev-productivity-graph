package com.devgraph.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A directed, typed, property-bearing edge between two node ids.
 *
 * <p>Edges carry properties too (e.g. a REVIEWED edge can carry {@code approved},
 * a TOUCHES edge can carry {@code linesChanged}) so that traversals can be
 * <em>weighted</em> — the difference between "developers who share a repo" and
 * "developers who actually review each other's code" is entirely in edge
 * properties.
 */
public final class Edge {
    private final String from;
    private final String to;
    private final EdgeType type;
    private final Map<String, Object> properties;

    public Edge(String from, String to, EdgeType type, Map<String, Object> properties) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.properties = new HashMap<>(properties == null ? Map.of() : properties);
    }

    public Edge(String from, String to, EdgeType type) {
        this(from, to, type, Map.of());
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public EdgeType type() {
        return type;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Object prop(String key) {
        return properties.get(key);
    }

    /** The node at the far end when traversing from {@code nodeId}. */
    public String other(String nodeId) {
        if (nodeId.equals(from)) return to;
        if (nodeId.equals(to)) return from;
        throw new IllegalArgumentException(nodeId + " is not an endpoint of " + this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Edge edge)) return false;
        return from.equals(edge.from) && to.equals(edge.to) && type == edge.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, type);
    }

    @Override
    public String toString() {
        return from + " -" + type + "-> " + to;
    }
}
