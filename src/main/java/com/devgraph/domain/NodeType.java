package com.devgraph.domain;

/**
 * The vertex labels in the Developer Productivity Graph.
 *
 * <p>These map one-to-one to the entities in the delivery lifecycle:
 * a {@link #DEVELOPER} commits code, which lives in a {@link #REPOSITORY},
 * whose changes trigger a {@link #BUILD}, which produces a {@link #DEPLOYMENT},
 * which may create an {@link #INCIDENT}. A {@link #PULL_REQUEST} is the unit of
 * review, and a {@link #FILE} is the finest-grained unit of ownership — the level
 * at which "who knows this code?" is actually decided.
 */
public enum NodeType {
    DEVELOPER,
    REPOSITORY,
    FILE,
    COMMIT,
    PULL_REQUEST,
    BUILD,
    DEPLOYMENT,
    INCIDENT
}
