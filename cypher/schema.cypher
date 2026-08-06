// ============================================================================
//  schema.cypher — constraints & indexes for the Developer Productivity Graph
// ----------------------------------------------------------------------------
//  Run this ONCE before seeding. It mirrors what Neo4jGraphStore creates
//  programmatically at startup: a uniqueness constraint on `id` per node label
//  (which also provides a fast lookup index for every MATCH ... {id:$id}).
//
//  Labels map 1:1 to NodeType; relationship types map 1:1 to EdgeType.
// ============================================================================

CREATE CONSTRAINT developer_id   IF NOT EXISTS FOR (n:DEVELOPER)    REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT repository_id  IF NOT EXISTS FOR (n:REPOSITORY)   REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT file_id        IF NOT EXISTS FOR (n:FILE)         REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT commit_id      IF NOT EXISTS FOR (n:COMMIT)       REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT pull_request_id IF NOT EXISTS FOR (n:PULL_REQUEST) REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT build_id       IF NOT EXISTS FOR (n:BUILD)        REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT deployment_id  IF NOT EXISTS FOR (n:DEPLOYMENT)   REQUIRE n.id IS UNIQUE;
CREATE CONSTRAINT incident_id    IF NOT EXISTS FOR (n:INCIDENT)     REQUIRE n.id IS UNIQUE;
