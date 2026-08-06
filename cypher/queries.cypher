// ============================================================================
//  queries.cypher — the analytic queries, as Cypher
// ----------------------------------------------------------------------------
//  Each query below is the graph-native expression of one analytic. They are
//  PARAMETERISED: values are passed as $params, never string-concatenated. In
//  cypher-shell set a parameter first, e.g.
//
//      :param repositoryId => 'repo-payments';
//      :param incidentId   => 'inc-1';
//      :param minStrength  => 1.0;
//      :param fromId       => 'dev-frank';
//      :param toId         => 'inc-1';
//
//  (The application passes the same queries via the official Neo4j Java driver
//   with parameters(...) — see Neo4jAnalytics in the app for the wired versions.)
//
//  "Why a graph?" markers below call out where a relational engine would need
//  stacked recursive CTEs or repeated self-joins.
// ============================================================================


// ----------------------------------------------------------------------------
// 1. KNOWLEDGE SILOS  — files known to exactly one developer
//    Hops: FILE <-TOUCHES- COMMIT <-AUTHORED- DEVELOPER  (2 hops)
//    Scope: whole graph when $repositoryId IS NULL, else one repo.
// ----------------------------------------------------------------------------
MATCH (f:FILE)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
WHERE $repositoryId IS NULL
   OR EXISTS { (f)-[:IN_REPO]->(:REPOSITORY {id:$repositoryId}) }
WITH f, collect(DISTINCT d) AS authors
WHERE size(authors) = 1
OPTIONAL MATCH (f)-[:IN_REPO]->(r:REPOSITORY)
RETURN f.id                AS fileId,
       f.path              AS filePath,
       r.id                AS repositoryId,
       head(authors).id    AS soleOwnerId,
       head(authors).name  AS soleOwnerName
ORDER BY filePath;


// ----------------------------------------------------------------------------
// 2. BUS FACTOR (raw material) — per file, the dominant author and their weight
//    Hops: REPOSITORY <-IN_REPO- FILE <-TOUCHES- COMMIT <-AUTHORED- DEVELOPER (3 hops)
//    The greedy "accumulate to >50%" step is done by the caller; this query
//    returns the per-file dominant author, which is the awkward part in SQL
//    (argmax-per-group over a multi-join).
// ----------------------------------------------------------------------------
MATCH (r:REPOSITORY {id:$repositoryId})<-[:IN_REPO]-(f:FILE)
OPTIONAL MATCH (f)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
WITH f, d, count(*) AS commits
ORDER BY commits DESC
WITH f, collect({dev:d, commits:commits}) AS ranked
WITH f,
     head(ranked).dev            AS dominantDev,
     size([x IN ranked WHERE x.dev IS NOT NULL]) AS authorCount
WHERE dominantDev IS NOT NULL
RETURN dominantDev.id            AS dominantDeveloperId,
       dominantDev.name          AS dominantDeveloperName,
       count(f)                  AS filesDominated,
       sum(CASE WHEN authorCount = 1 THEN 1 ELSE 0 END) AS soleOwnedFiles
ORDER BY filesDominated DESC;


// ----------------------------------------------------------------------------
// 3. COLLABORATION  — derived developer-to-developer edges
//    The relationship is COMPUTED, never stored — the whole point of the model.
//    strength = 2 * sharedReviews + coEditedFiles.
//
//    3a. shared reviews: A reviewed a PR that B authored (via B's commit).
//        Hops: DEVELOPER -REVIEWED-> PR <-PART_OF- COMMIT <-AUTHORED- DEVELOPER
// ----------------------------------------------------------------------------
MATCH (a:DEVELOPER)-[:REVIEWED]->(pr:PULL_REQUEST)<-[:PART_OF]-(:COMMIT)<-[:AUTHORED]-(b:DEVELOPER)
WHERE a <> b
WITH a, b, count(DISTINCT pr) AS reviews
// normalise the unordered pair so {A,B} and {B,A} aggregate together
WITH CASE WHEN a.id < b.id THEN a ELSE b END AS lo,
     CASE WHEN a.id < b.id THEN b ELSE a END AS hi,
     reviews
WITH lo, hi, sum(reviews) AS sharedReviews

// 3b. co-edited files: A and B both TOUCHED the same file (via their commits)
OPTIONAL MATCH (lo)-[:AUTHORED]->(:COMMIT)-[:TOUCHES]->(f:FILE)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(hi)
WITH lo, hi, sharedReviews, count(DISTINCT f) AS coEditedFiles
WITH lo, hi, sharedReviews, coEditedFiles,
     (2.0 * sharedReviews + coEditedFiles) AS strength
WHERE strength >= $minStrength AND strength > 0
RETURN lo.id AS developerA, hi.id AS developerB,
       sharedReviews, coEditedFiles, strength
ORDER BY strength DESC;


// ----------------------------------------------------------------------------
// 4. BLAME CASCADE  — THE showcase query.
//    Walk BACKWARDS from an incident to every implicated developer & file.
//    Hops (6+): INCIDENT <-CAUSED- DEPLOYMENT <-PRODUCED- BUILD
//               <-TRIGGERED- PULL_REQUEST <-PART_OF- COMMIT
//               then COMMIT -TOUCHES-> FILE and COMMIT <-AUTHORED- DEVELOPER
//
//    WHY A GRAPH: in SQL this is several stacked recursive CTEs (incident->
//    deployment->build->pr->commit) plus two more joins fanning out to files
//    and authors. Here it is a single variable-free path pattern.
// ----------------------------------------------------------------------------
MATCH (i:INCIDENT {id:$incidentId})
      <-[:CAUSED]-(dep:DEPLOYMENT)
      <-[:PRODUCED]-(b:BUILD)
      <-[:TRIGGERED]-(pr:PULL_REQUEST)
      <-[:PART_OF]-(c:COMMIT)
MATCH (c)<-[:AUTHORED]-(d:DEVELOPER)
MATCH (c)-[:TOUCHES]->(f:FILE)
WITH i, dep, b,
     collect(DISTINCT c.id)   AS implicatedCommits,
     collect(DISTINCT f.path) AS implicatedFiles,
     d, count(DISTINCT c)     AS commitsByDev
RETURN i.id                                          AS incidentId,
       i.title                                       AS incidentTitle,
       [dep.id, b.id]                                AS deploymentPath,
       implicatedCommits,
       implicatedFiles,
       collect({developerId:d.id, developerName:d.name,
                implicatedCommits:commitsByDev})      AS implicatedDevelopers;


// ----------------------------------------------------------------------------
// 5. SHORTEST PATH  — degrees of separation between any two nodes.
//    Variable-length undirected traversal — the canonical recursive-join pain
//    point in SQL, one clause in Cypher.
// ----------------------------------------------------------------------------
MATCH (from {id:$fromId}), (to {id:$toId})
MATCH p = shortestPath((from)-[*..15]-(to))
RETURN [n IN nodes(p) | n.id] AS path,
       length(p)              AS degrees;
