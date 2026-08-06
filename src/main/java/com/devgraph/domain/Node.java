package com.devgraph.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A property-graph vertex: a stable id, a {@link NodeType} label, and an
 * open-ended property bag.
 *
 * <p>We deliberately use a property bag rather than a class-per-entity hierarchy.
 * A graph store is schemaless by nature, and modeling every node type as a
 * distinct Java class would force the store and traversal code to be generic-over-T
 * or riddled with instanceof. A typed {@code id} + {@code type} + {@code Map}
 * mirrors exactly what a Neo4j node is, which is what keeps the in-memory store a
 * faithful, swappable stand-in for a real graph database.
 */
public final class Node {
    private final String id;
    private final NodeType type;
    private final Map<String, Object> properties;

    public Node(String id, NodeType type, Map<String, Object> properties) {
        this.id = id;
        this.type = type;
        this.properties = new HashMap<>(properties == null ? Map.of() : properties);
    }

    public String id() {
        return id;
    }

    public NodeType type() {
        return type;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Object prop(String key) {
        return properties.get(key);
    }

    public String str(String key) {
        Object v = properties.get(key);
        return v == null ? null : v.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return type + "(" + id + ")";
    }
}
