# Developer Productivity Graph

A small, complete Java 17 application that models software delivery as a **graph** —
developers, commits, repositories, files, pull requests, builds, deployments and
incidents — and answers the questions that only make sense as *traversals*:

- **Knowledge silos** — which files are known to exactly one person?
- **Bus factor** — how few people hold the majority of a repo's knowledge?
- **Collaboration patterns** — who actually works together (derived from reviews + co-editing), and what sub-teams emerge?
- **Blame cascade** — trace a production incident backwards through deployment → build → PR → commit → file → the developers implicated.

## Why a graph (and not a table)

Every headline question here is about **connections between entities**, not
attributes of one entity, and each is a different *shape* of graph traversal:

| Question | Graph operation | Why SQL struggles |
|---|---|---|
| Knowledge silo | `File <-TOUCHES- Commit <-AUTHORED- Developer`; author set of size 1 | fine as a join, but… |
| Bus factor | per-file dominant-author, then greedy cover to majority | multi-level aggregation over a recursive join |
| Collaboration | **derived** edge from review + co-edit set intersection | the relationship isn't stored — it's computed from structure |
| Collaboration clusters | connected components (union-find) of the derived graph | recursive CTE + component labeling |
| **Blame cascade** | variable-length **reverse path** with fan-out at the commit level | several stacked recursive CTEs; awkward and slow |
| Degrees of separation | BFS shortest path | classic recursive-join pain |

The clinching case is the **blame cascade**: `Incident <-CAUSED- Deployment
<-PRODUCED- Build <-TRIGGERED- PullRequest <-PART_OF- Commit -TOUCHES-> File`,
plus `Commit <-AUTHORED- Developer`. That is a multi-hop reverse path — exactly
what a graph does in a few neighborhood walks and what a relational schema forces
into layered recursive CTEs. The information you want lives in the **edges and
paths**, not the rows.

## Architecture

**Stack:** Spring Boot 3.3 (Spring MVC + embedded Tomcat), Java 17.

```
com.devgraph
├── Main                                 @SpringBootApplication (entry point)
├── config/     GraphConfig             @Configuration — beans: GraphStore, ProductivityAnalytics
├── domain/     Node, Edge, NodeType, EdgeType, Direction   (property-graph primitives)
├── graph/      GraphStore (interface)  ← the swap seam
│               InMemoryGraphStore       (dual in/out adjacency indexes, concurrent-safe)
├── service/    ProductivityAnalytics    (all traversals: bus factor, silos,
│               Insights                   collaboration, clustering, blame, path)
├── seed/       SeedData                 (a deliberately-shaped demo graph)
├── api/        AnalyticsController      @RestController — the /api/* endpoints
│               ApiExceptionHandler      @RestControllerAdvice — maps errors to 404/400
└── resources/static/index.html         demo frontend, served by Spring at /
```

**The `GraphStore` seam is the key engineering decision.** Every analytic is
written against six primitives (add node/edge, get node, nodes-by-type,
edges/neighbors in a direction). Nothing above the store knows *how* the graph is
persisted. Swapping the in-memory store for a `Neo4jGraphStore` — where
`neighbors()` becomes a one-line Cypher `MATCH` — requires changing exactly one
line in `GraphConfig.graphStore()` and touches no service or API code.

### Layer responsibilities
- **Store** does what a graph store is good at: O(degree) neighborhood lookups in either direction (dual adjacency indexes).
- **Service** owns the algorithms — BFS, set intersection, greedy cover, union-find — in plain, testable Java. It has **no Spring dependency**, so it stays fast to unit-test in isolation.
- **Controllers** are a thin Spring MVC surface; adding an API is just another `@GetMapping`/`@PostMapping`.
- **Config** does the DI wiring (the DI equivalent of the old hand-rolled `main()`).

## Data model

**Nodes:** `DEVELOPER, REPOSITORY, FILE, COMMIT, PULL_REQUEST, BUILD, DEPLOYMENT, INCIDENT`

**Edges (directed, property-bearing):**
`AUTHORED` (dev→commit), `TOUCHES` (commit→file), `IN_REPO` (file→repo),
`PART_OF` (commit→PR), `REVIEWED` (dev→PR), `TRIGGERED` (PR/commit→build),
`PRODUCED` (build→deployment), `CAUSED` (deployment→incident).

`FILE` granularity is deliberate: ownership and silos are decided at the file
level, not the repo level. `COMMITS_WITH`/`COLLABORATES` is **never stored** — it
is computed, which is the whole point.

## Build & run

Requires **Java 17+**. Maven is **not** required — the bundled Maven Wrapper
(`./mvnw`) downloads the pinned Maven version (3.9.9) on first use. On Windows use
`mvnw.cmd`. (If you already have Maven installed, `mvn` works too.)

```bash
./mvnw test                    # 15 tests: 9 pure-Java unit + 6 Spring web-layer (MockMvc)
export NEO4J_USER=''           # set uname for neo4j
export NEO4J_PASSWORD=''       # set password for neo4j
export NEO4J_URI=''            # set url for neo4j
./mvnw clean install
./mvnw spring-boot:run         # start on http://localhost:8080
```

Then open **http://localhost:8080/** for the demo UI (a static page served by
Spring from `resources/static/`) 

## Endpoints

All under `/api`:

```bash
curl localhost:8080/api/health
curl localhost:8080/api/repositories/repo-web/bus-factor
curl localhost:8080/api/repositories/repo-payments/bus-factor
curl localhost:8080/api/repositories/all/silos          # repoId "all" = whole graph
curl 'localhost:8080/api/collaborations?minStrength=1'
curl 'localhost:8080/api/clusters?minStrength=3'
curl localhost:8080/api/incidents/inc-1/blame
curl 'localhost:8080/api/path?from=dev-frank&to=inc-1'
```

## Loading the graph from a script

The database can be populated **without booting the app**, straight from Cypher:

```bash
export NEO4J_URI='neo4j+s://<your-db>.databases.neo4j.io'
export NEO4J_USER='neo4j'
export NEO4J_PASSWORD='********'      # never committed — read from the environment
./scripts/load.sh                     # applies schema, seeds data, prints counts
./scripts/load.sh --reset             # wipe the graph first, then seed
```

`scripts/load.sh` runs [`cypher/schema.cypher`](cypher/schema.cypher) (uniqueness
constraints on `id` per label) then [`cypher/seed.cypher`](cypher/seed.cypher) via
`cypher-shell`. The seed script is **idempotent** (`MERGE` on natural keys) and
reproduces the exact same graph that `SeedData.java` builds at startup, so the two
paths never diverge.

## Queries explained

All analytic queries live in [`cypher/queries.cypher`](cypher/queries.cypher).
They are **parameterised** — values are passed as `$params`, never
string-concatenated into the query text — and every one is a *traversal*, not a
single-table lookup. Set parameters in `cypher-shell` first, e.g.
`:param incidentId => 'inc-1';`.

### 1. Knowledge silos — files known to exactly one developer *(2 hops)*
`FILE <-TOUCHES- COMMIT <-AUTHORED- DEVELOPER`; a silo is a file whose author set
has size 1.

```cypher
MATCH (f:FILE)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
WHERE $repositoryId IS NULL
   OR EXISTS { (f)-[:IN_REPO]->(:REPOSITORY {id:$repositoryId}) }
WITH f, collect(DISTINCT d) AS authors
WHERE size(authors) = 1
RETURN f.id AS fileId, f.path AS filePath, head(authors).name AS soleOwnerName;
```

### 2. Bus factor — per-file dominant author *(3 hops)*
`REPOSITORY <-IN_REPO- FILE <-TOUCHES- COMMIT <-AUTHORED- DEVELOPER`, then the
*argmax-per-file* author. The greedy "accumulate owners to >50% of files" step is
done by the caller. The argmax-per-group over a multi-join is the part a
relational engine finds awkward.

```cypher
MATCH (r:REPOSITORY {id:$repositoryId})<-[:IN_REPO]-(f:FILE)
OPTIONAL MATCH (f)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(d:DEVELOPER)
WITH f, d, count(*) AS commits
ORDER BY commits DESC
WITH f, collect({dev:d, commits:commits}) AS ranked
WHERE head(ranked).dev IS NOT NULL
RETURN head(ranked).dev.name AS dominantDeveloper, count(f) AS filesDominated
ORDER BY filesDominated DESC;
```

### 3. Collaboration — a *derived* relationship
Two developers collaborate if one reviewed the other's PRs and/or they co-edited
files. **This edge is never stored — it is computed from the review + authorship
structure**, which is the whole argument for the graph. Strength is
`2 × sharedReviews + coEditedFiles` (reviews are direct human interaction, so they
count double).

```cypher
MATCH (a:DEVELOPER)-[:REVIEWED]->(pr:PULL_REQUEST)<-[:PART_OF]-(:COMMIT)<-[:AUTHORED]-(b:DEVELOPER)
WHERE a <> b
WITH CASE WHEN a.id < b.id THEN a ELSE b END AS lo,
     CASE WHEN a.id < b.id THEN b ELSE a END AS hi,
     count(DISTINCT pr) AS reviews
WITH lo, hi, sum(reviews) AS sharedReviews
OPTIONAL MATCH (lo)-[:AUTHORED]->(:COMMIT)-[:TOUCHES]->(f:FILE)<-[:TOUCHES]-(:COMMIT)<-[:AUTHORED]-(hi)
WITH lo, hi, sharedReviews, count(DISTINCT f) AS coEditedFiles
WITH lo, hi, sharedReviews, coEditedFiles, (2.0*sharedReviews + coEditedFiles) AS strength
WHERE strength >= $minStrength
RETURN lo.id AS developerA, hi.id AS developerB, sharedReviews, coEditedFiles, strength
ORDER BY strength DESC;
```

### 4. Blame cascade — the showcase query *(6+ hops)*
Walk **backwards** from an incident to every implicated developer and file:
`INCIDENT <-CAUSED- DEPLOYMENT <-PRODUCED- BUILD <-TRIGGERED- PULL_REQUEST
<-PART_OF- COMMIT`, then fan out `COMMIT -TOUCHES-> FILE` and
`COMMIT <-AUTHORED- DEVELOPER`. **This is the query a relational database finds
awkward** — it is several stacked recursive CTEs plus two more joins; in Cypher it
is one path pattern.

```cypher
MATCH (i:INCIDENT {id:$incidentId})
      <-[:CAUSED]-(dep:DEPLOYMENT)
      <-[:PRODUCED]-(b:BUILD)
      <-[:TRIGGERED]-(pr:PULL_REQUEST)
      <-[:PART_OF]-(c:COMMIT)
MATCH (c)<-[:AUTHORED]-(d:DEVELOPER)
MATCH (c)-[:TOUCHES]->(f:FILE)
RETURN i.title AS incidentTitle,
       [dep.id, b.id]           AS deploymentPath,
       collect(DISTINCT c.id)   AS implicatedCommits,
       collect(DISTINCT f.path) AS implicatedFiles,
       collect(DISTINCT d.name) AS implicatedDevelopers;
```

Against the seed graph this returns Bob, commit `c2`, and `checkout.js` — while
Alice's `c1` (which flowed to a *healthy* deploy) is correctly **not** implicated.

### 5. Shortest path — degrees of separation
A variable-length undirected traversal — the classic recursive-join pain point in
SQL, one clause in Cypher.

```cypher
MATCH (from {id:$fromId}), (to {id:$toId})
MATCH p = shortestPath((from)-[*..15]-(to))
RETURN [n IN nodes(p) | n.id] AS path, length(p) AS degrees;
```

> **Note on the running app.** For zero-dependency startup and fast unit tests,
> the shipped service (`ProductivityAnalytics`) computes these same results in
> Java over the `GraphStore` neighbourhood primitives. The Cypher above is the
> graph-native statement of each analytic and runs directly against Neo4j (via
> `cypher-shell`, the Neo4j Browser, or the official Java driver's parameterised
> API).

## The seed graph tells a story

The demo data is engineered so every query returns something meaningful:

- **`payments-service` has a bus factor of 1.** Dana dominates all 3 files and is
  the *sole* author of `crypto.go` and `refund.go` — a textbook silo.
- **Two real teams + one bridge.** Alice/Bob/Carol review each other on the web
  repo; Dana/Erin work payments. **Frank reviews across both.**
  - `GET /clusters?minStrength=3` → two clean teams.
  - `GET /clusters?minStrength=1` → Frank's weak cross-team reviews **fuse all six
    into one component.** That collapse *is* the detection of a connector: the
    threshold is a knob, and watching the components merge tells you who holds the
    org together.
- **Incident INC-1 traces cleanly to Bob.** `pr-2` (Bob's commit `c2`, touching
  `checkout.js`) → `build-1` → `deploy-1` → `inc-1`. Meanwhile Alice's `pr-1`
  flowed to a *healthy* deploy, so `c1` is correctly **not** implicated — the
  cascade discriminates the guilty path from the innocent one.


### repo-web collaboration cluster (Alice / Bob / Carol)

```text
        AUTHORED                 PART_OF
 [Alice] ───────► <c1> ──────────────────► «pr-1»
                    │ TOUCHES
                    ├──► {checkout.js}
                    └──► {cart.js}

 [Bob]   ───────► <c2> ──────────────────► «pr-2»
                    │
                    ├──► {checkout.js}
                    └──► {nav.js}

 [Carol] ───────► <c3> ──────────────────► «pr-3»
                    │
                    ├──► {cart.js}
                    └──► {nav.js}

 Reviews (human collaboration):
   [Bob]   ─REVIEWED─► «pr-1»      [Alice] ─REVIEWED─► «pr-2»
   [Carol] ─REVIEWED─► «pr-1»      [Carol] ─REVIEWED─► «pr-2»
                                   [Alice] ─REVIEWED─► «pr-3»

 All three files live IN_REPO ─► (repo-web)
```

### Collaboration triangle (Alice / Bob / Carol)

```text
        Alice
       ╱     ╲
   reviews   reviews
     ╱          ╲
   Bob ◄──────► Carol
       reviews
```

### repo-payments cluster + the silo (Dana / Erin)

```text
 [Dana] ─AUTHORED─► <c4> ─PART_OF─► «pr-4»
                      ├─TOUCHES─► {charge.go}
                      └─TOUCHES─► {crypto.go}   ◄── SILO
 [Dana] ─AUTHORED─► <c5> ─PART_OF─► «pr-5»
                      ├─TOUCHES─► {refund.go}
                      └─TOUCHES─► {crypto.go}   ◄── SILO (only Dana, ever)
 [Erin] ─AUTHORED─► <c6> ─PART_OF─► «pr-6»
                      └─TOUCHES─► {charge.go}

 Reviews:  [Erin] ─REVIEWED─► «pr-4»     [Dana] ─REVIEWED─► «pr-6»

 All three files live IN_REPO ─► (repo-payments)
```

### Frank the cross-team bridge

```text
                          reviews
        repo-web  ◄─────── [Frank] ───────►  repo-payments
       (via «pr-2»)                          (via «pr-4»)
```

### Build / Deploy / Incident chains

```text
 CHAIN A (incident — the blame cascade):
   «pr-2» ─TRIGGERED─► ⟦build-1 GREEN #101⟧ ─PRODUCED─► ⟦deploy-1 prod⟧ ─CAUSED─► ⟦inc-1 SEV1⟧
     ▲
     └── PART_OF ── <c2> ── TOUCHES ──► {checkout.js}
                     ▲
                     └─ AUTHORED ─ [Bob]

   Walking backward from inc-1 blames:  inc-1 ← deploy-1 ← build-1 ← pr-2 ← c2 ← Bob / checkout.js

 CHAIN B (healthy — no incident):
   «pr-1» ─TRIGGERED─► ⟦build-2 GREEN #102⟧ ─PRODUCED─► ⟦deploy-2 prod⟧

 CHAIN C (failed build — no deployment):
   «pr-3» ─TRIGGERED─► ⟦build-3 RED #103, failedTests=4⟧
```

### The whole graph at a glance

```text
                    ┌───────────────── repo-web ─────────────────┐
   Alice─Bob─Carol  │ checkout.js  cart.js  nav.js                │
   (review triangle)│   pr-1(→build-2→deploy-2)                   │
                    │   pr-2(→build-1→deploy-1→INCIDENT inc-1)    │
                    │   pr-3(→build-3 RED)                        │
                    └───────────────┬─────────────────────────────┘
                                    │ Frank (reviews pr-2 & pr-4)
                    ┌───────────────┴──────── repo-payments ──────┐
   Dana ── Erin     │ charge.go  refund.go  crypto.go(SILO=Dana)  │
   (Dana dominates) │   pr-4, pr-5 (Dana)   pr-6 (Erin)           │
                    └─────────────────────────────────────────────┘
```

