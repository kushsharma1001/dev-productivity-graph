package com.devgraph.domain;

/**
 * Traversal direction relative to a node when querying its incident edges.
 *
 * <p>Graph algorithms constantly need to ask "edges leaving this node"
 * (OUT), "edges arriving at this node" (IN), or "both". Making this explicit
 * at the store level keeps the service layer readable: e.g. "files this
 * developer TOUCHES" is OUT from the developer, whereas "developers who
 * TOUCHED this file" is IN to the file.
 */
public enum Direction {
    OUT,
    IN,
    BOTH
}
