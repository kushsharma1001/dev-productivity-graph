// ============================================================================
//  seed.cypher — the deliberately-shaped demo graph
// ----------------------------------------------------------------------------
//  This is the exact same graph that SeedData.java builds at application
//  startup, expressed as a standalone, re-runnable Cypher script so the
//  database can be populated WITHOUT booting the app (e.g. from cypher-shell
//  or the Neo4j Browser). See scripts/load.sh for a one-command loader.
//
//  Idempotent: every node/edge is MERGE'd on its natural key, so re-running
//  updates rather than duplicates. To start from a clean graph, uncomment the
//  reset line below.
//
//  The topology is engineered so every analytic returns something meaningful:
//    * payments-service has bus factor 1 (Dana dominates; crypto.go is a silo)
//    * two collaboration clusters (web trio, payments pair) + Frank the bridge
//    * incident inc-1 traces back through deploy-1 <- build-1 <- pr-2 <- c2 <- Bob
// ============================================================================

// --- OPTIONAL RESET: wipe the whole graph before seeding -------------------
// MATCH (n) DETACH DELETE n;

// ---- Developers ------------------------------------------------------------
// Canonical form throughout: MERGE on the natural key (id), then SET properties.
MERGE (alice:DEVELOPER {id:'dev-alice'}) SET alice.name = 'Alice';
MERGE (bob:DEVELOPER   {id:'dev-bob'})   SET bob.name   = 'Bob';
MERGE (carol:DEVELOPER {id:'dev-carol'}) SET carol.name = 'Carol';
MERGE (dana:DEVELOPER  {id:'dev-dana'})  SET dana.name  = 'Dana';
MERGE (erin:DEVELOPER  {id:'dev-erin'})  SET erin.name  = 'Erin';
MERGE (frank:DEVELOPER {id:'dev-frank'}) SET frank.name = 'Frank';

// ---- Repositories ----------------------------------------------------------
MERGE (web:REPOSITORY {id:'repo-web'})           SET web.name = 'web-frontend';
MERGE (pay:REPOSITORY {id:'repo-payments'})      SET pay.name = 'payments-service';

// ---- Files (FILE -[:IN_REPO]-> REPOSITORY) ---------------------------------
MERGE (fCheckout:FILE {id:'file-checkout'}) SET fCheckout.path = 'web/checkout.js';
MERGE (fCart:FILE     {id:'file-cart'})     SET fCart.path     = 'web/cart.js';
MERGE (fNav:FILE      {id:'file-nav'})      SET fNav.path      = 'web/nav.js';
MERGE (fCharge:FILE   {id:'file-charge'})   SET fCharge.path   = 'payments/charge.go';
MERGE (fRefund:FILE   {id:'file-refund'})   SET fRefund.path   = 'payments/refund.go';
MERGE (fCrypto:FILE   {id:'file-crypto'})   SET fCrypto.path   = 'payments/crypto.go';   // the silo — only Dana

MATCH (f:FILE {id:'file-checkout'}), (r:REPOSITORY {id:'repo-web'})      MERGE (f)-[:IN_REPO]->(r);
MATCH (f:FILE {id:'file-cart'}),     (r:REPOSITORY {id:'repo-web'})      MERGE (f)-[:IN_REPO]->(r);
MATCH (f:FILE {id:'file-nav'}),      (r:REPOSITORY {id:'repo-web'})      MERGE (f)-[:IN_REPO]->(r);
MATCH (f:FILE {id:'file-charge'}),   (r:REPOSITORY {id:'repo-payments'}) MERGE (f)-[:IN_REPO]->(r);
MATCH (f:FILE {id:'file-refund'}),   (r:REPOSITORY {id:'repo-payments'}) MERGE (f)-[:IN_REPO]->(r);
MATCH (f:FILE {id:'file-crypto'}),   (r:REPOSITORY {id:'repo-payments'}) MERGE (f)-[:IN_REPO]->(r);

// ---- Commits + PRs ---------------------------------------------------------
// COMMIT {message}, PULL_REQUEST {title}
MERGE (c:COMMIT {id:'c1'}) SET c.message = 'c1';
MERGE (c:COMMIT {id:'c2'}) SET c.message = 'c2';
MERGE (c:COMMIT {id:'c3'}) SET c.message = 'c3';
MERGE (c:COMMIT {id:'c4'}) SET c.message = 'c4';
MERGE (c:COMMIT {id:'c5'}) SET c.message = 'c5';
MERGE (c:COMMIT {id:'c6'}) SET c.message = 'c6';

MERGE (p:PULL_REQUEST {id:'pr-1'}) SET p.title = 'pr-1';
MERGE (p:PULL_REQUEST {id:'pr-2'}) SET p.title = 'pr-2';
MERGE (p:PULL_REQUEST {id:'pr-3'}) SET p.title = 'pr-3';
MERGE (p:PULL_REQUEST {id:'pr-4'}) SET p.title = 'pr-4';
MERGE (p:PULL_REQUEST {id:'pr-5'}) SET p.title = 'pr-5';
MERGE (p:PULL_REQUEST {id:'pr-6'}) SET p.title = 'pr-6';

// ---- AUTHORED (DEVELOPER -> COMMIT) ----------------------------------------
MATCH (d:DEVELOPER {id:'dev-alice'}), (c:COMMIT {id:'c1'}) MERGE (d)-[:AUTHORED]->(c);
MATCH (d:DEVELOPER {id:'dev-bob'}),   (c:COMMIT {id:'c2'}) MERGE (d)-[:AUTHORED]->(c);
MATCH (d:DEVELOPER {id:'dev-carol'}), (c:COMMIT {id:'c3'}) MERGE (d)-[:AUTHORED]->(c);
MATCH (d:DEVELOPER {id:'dev-dana'}),  (c:COMMIT {id:'c4'}) MERGE (d)-[:AUTHORED]->(c);
MATCH (d:DEVELOPER {id:'dev-dana'}),  (c:COMMIT {id:'c5'}) MERGE (d)-[:AUTHORED]->(c);
MATCH (d:DEVELOPER {id:'dev-erin'}),  (c:COMMIT {id:'c6'}) MERGE (d)-[:AUTHORED]->(c);

// ---- PART_OF (COMMIT -> PULL_REQUEST) --------------------------------------
MATCH (c:COMMIT {id:'c1'}), (p:PULL_REQUEST {id:'pr-1'}) MERGE (c)-[:PART_OF]->(p);
MATCH (c:COMMIT {id:'c2'}), (p:PULL_REQUEST {id:'pr-2'}) MERGE (c)-[:PART_OF]->(p);
MATCH (c:COMMIT {id:'c3'}), (p:PULL_REQUEST {id:'pr-3'}) MERGE (c)-[:PART_OF]->(p);
MATCH (c:COMMIT {id:'c4'}), (p:PULL_REQUEST {id:'pr-4'}) MERGE (c)-[:PART_OF]->(p);
MATCH (c:COMMIT {id:'c5'}), (p:PULL_REQUEST {id:'pr-5'}) MERGE (c)-[:PART_OF]->(p);
MATCH (c:COMMIT {id:'c6'}), (p:PULL_REQUEST {id:'pr-6'}) MERGE (c)-[:PART_OF]->(p);

// ---- TOUCHES (COMMIT -> FILE {lines:20}) -----------------------------------
MATCH (c:COMMIT {id:'c1'}), (f:FILE {id:'file-checkout'}) MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c1'}), (f:FILE {id:'file-cart'})     MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c2'}), (f:FILE {id:'file-checkout'}) MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c2'}), (f:FILE {id:'file-nav'})      MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c3'}), (f:FILE {id:'file-cart'})     MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c3'}), (f:FILE {id:'file-nav'})      MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c4'}), (f:FILE {id:'file-charge'})   MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c4'}), (f:FILE {id:'file-crypto'})   MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c5'}), (f:FILE {id:'file-refund'})   MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c5'}), (f:FILE {id:'file-crypto'})   MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;
MATCH (c:COMMIT {id:'c6'}), (f:FILE {id:'file-charge'})   MERGE (c)-[t:TOUCHES]->(f) SET t.lines = 20;

// ---- REVIEWED (DEVELOPER -> PULL_REQUEST {approved:true}) -------------------
MATCH (d:DEVELOPER {id:'dev-bob'}),   (p:PULL_REQUEST {id:'pr-1'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-carol'}), (p:PULL_REQUEST {id:'pr-1'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-alice'}), (p:PULL_REQUEST {id:'pr-2'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-carol'}), (p:PULL_REQUEST {id:'pr-2'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-alice'}), (p:PULL_REQUEST {id:'pr-3'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-erin'}),  (p:PULL_REQUEST {id:'pr-4'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-dana'}),  (p:PULL_REQUEST {id:'pr-6'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
// Frank is the cross-team bridge: reviews in both repos.
MATCH (d:DEVELOPER {id:'dev-frank'}), (p:PULL_REQUEST {id:'pr-2'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;
MATCH (d:DEVELOPER {id:'dev-frank'}), (p:PULL_REQUEST {id:'pr-4'}) MERGE (d)-[x:REVIEWED]->(p) SET x.approved = true;

// ---- Build / Deploy / Incident chains --------------------------------------
// CHAIN A (the incident): pr-2 -> build-1 -> deploy-1 -> inc-1
MERGE (b:BUILD {id:'build-1'}) SET b.status = 'GREEN', b.number = 101;
MERGE (dp:DEPLOYMENT {id:'deploy-1'}) SET dp.env = 'production';
MERGE (i:INCIDENT {id:'inc-1'}) SET i.title = 'Checkout 500s after deploy', i.severity = 'SEV1';
MATCH (p:PULL_REQUEST {id:'pr-2'}), (b:BUILD {id:'build-1'})      MERGE (p)-[:TRIGGERED]->(b);
MATCH (b:BUILD {id:'build-1'}), (dp:DEPLOYMENT {id:'deploy-1'})   MERGE (b)-[:PRODUCED]->(dp);
MATCH (dp:DEPLOYMENT {id:'deploy-1'}), (i:INCIDENT {id:'inc-1'})  MERGE (dp)-[:CAUSED]->(i);

// CHAIN B (healthy): pr-1 -> build-2 -> deploy-2 (no incident)
MERGE (b:BUILD {id:'build-2'}) SET b.status = 'GREEN', b.number = 102;
MERGE (dp:DEPLOYMENT {id:'deploy-2'}) SET dp.env = 'production';
MATCH (p:PULL_REQUEST {id:'pr-1'}), (b:BUILD {id:'build-2'})    MERGE (p)-[:TRIGGERED]->(b);
MATCH (b:BUILD {id:'build-2'}), (dp:DEPLOYMENT {id:'deploy-2'}) MERGE (b)-[:PRODUCED]->(dp);

// CHAIN C (failed build): pr-3 -> build-3 RED (no deployment)
MERGE (b:BUILD {id:'build-3'}) SET b.status = 'RED', b.number = 103, b.failedTests = 4;
MATCH (p:PULL_REQUEST {id:'pr-3'}), (b:BUILD {id:'build-3'})    MERGE (p)-[:TRIGGERED]->(b);
