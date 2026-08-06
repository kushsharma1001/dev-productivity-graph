package com.devgraph.domain;

/**
 * The relationship types in the Developer Productivity Graph.
 *
 * <p>The whole premise of this project is that the <em>edges</em> carry the
 * interesting information. A commit table tells you who committed; the
 * {@code AUTHORED -> TOUCHES -> ... -> CAUSED} chain tells you that a specific
 * developer's change to a specific file flowed through a build and a deployment
 * into a production incident. That chain is a path, not a row.
 *
 * <p>Directionality convention: edges point in the direction of causality / the
 * natural verb. {@code DEVELOPER -AUTHORED-> COMMIT} reads "developer authored commit".
 */
public enum EdgeType {
    /** Developer -> Commit. "Committed" in the problem statement. */
    AUTHORED,
    /** Commit -> File. The blast radius of a change at file granularity. */
    TOUCHES,
    /** File -> Repository. Locates a file within its repo. */
    IN_REPO,
    /** Commit -> PullRequest. The commit was delivered via this PR. */
    PART_OF,
    /** Developer -> PullRequest. A review relationship — the basis of collaboration edges. */
    REVIEWED,
    /** PullRequest -> Build / Commit -> Build. "Triggered" in the problem statement. */
    TRIGGERED,
    /** Build -> Deployment. "Created" — a green build produced a deployment. */
    PRODUCED,
    /** Deployment -> Incident. "Created" — a deployment caused a production incident. */
    CAUSED,
    /** Build -> (test failure marker). Modeled as a property on the build; see FAILED_TEST. */
    FAILED_TEST,
    /** Incident -> File (derived) / Commit -> Incident (derived by cascade). Blame linkage. */
    IMPLICATED_IN
}
