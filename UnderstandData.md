## Understand the data seeded into the CognoDB on app startup

The application starts by creating a complete developer-productivity graph. It contains **32 nodes** and **44 relationships** representing developers, repositories, files, commits, pull requests, reviews, builds, deployments, and an incident.

## Developers

```text
dev-alice — Alice
dev-bob — Bob
dev-carol — Carol
dev-dana — Dana
dev-erin — Erin
dev-frank — Frank
```

## Repositories

```text
repo-web — web-frontend
repo-payments — payments-service
```

## Files

The `web-frontend` repository contains:

```text
file-checkout — web/checkout.js
file-cart — web/cart.js
file-nav — web/nav.js
```

The `payments-service` repository contains:

```text
file-charge — payments/charge.go
file-refund — payments/refund.go
file-crypto — payments/crypto.go
```

`payments/crypto.go` is deliberately designed as a knowledge silo because only Dana has worked on it.

## Commits and Pull Requests

Alice creates commit `c1`, which belongs to pull request `pr-1`.

```text
c1 → pr-1
Files changed:
- web/checkout.js
- web/cart.js
```

Bob creates commit `c2`, which belongs to pull request `pr-2`.

```text
c2 → pr-2
Files changed:
- web/checkout.js
- web/nav.js
```

Carol creates commit `c3`, which belongs to pull request `pr-3`.

```text
c3 → pr-3
Files changed:
- web/cart.js
- web/nav.js
```

Dana creates commit `c4`, which belongs to pull request `pr-4`.

```text
c4 → pr-4
Files changed:
- payments/charge.go
- payments/crypto.go
```

Dana also creates commit `c5`, which belongs to pull request `pr-5`.

```text
c5 → pr-5
Files changed:
- payments/refund.go
- payments/crypto.go
```

Erin creates commit `c6`, which belongs to pull request `pr-6`.

```text
c6 → pr-6
Files changed:
- payments/charge.go
```

Every file-touch relationship stores:

```text
lines: 20
```

## Pull Request Reviews

The seeded reviews create realistic collaboration patterns.

```text
Bob reviews pr-1
Carol reviews pr-1

Alice reviews pr-2
Carol reviews pr-2

Alice reviews pr-3

Erin reviews pr-4
Dana reviews pr-6

Frank reviews pr-2
Frank reviews pr-4
```

Every review is marked as:

```text
approved: true
```

Frank is important in the demo because he reviews pull requests across both teams. This makes him a bridge between the web and payments collaboration clusters.

## Builds and Deployments

The project includes three builds.

```text
build-1
Status: GREEN
Build number: 101
Triggered by: pr-2
```

```text
build-2
Status: GREEN
Build number: 102
Triggered by: pr-1
```

```text
build-3
Status: RED
Build number: 103
Triggered by: pr-3
Failed tests: 4
```

Two successful builds produce production deployments.

```text
build-1 → deploy-1
Environment: production
```

```text
build-2 → deploy-2
Environment: production
```

## Production Incident

The first deployment causes a production incident.

```text
deploy-1 → inc-1

Incident title: Checkout 500s after deploy
Severity: SEV1
```

The complete incident path is:

```text
Bob
→ commit c2
→ pull request pr-2
→ build-1
→ deploy-1
→ incident inc-1
```

Because Bob's commit changed `web/checkout.js` and `web/nav.js`, the application's blame-cascade analysis identifies Bob and these files as connected to the incident.

<img width="1866" height="2184" alt="Image" src="https://github.com/user-attachments/assets/ce0b6da2-600a-4532-8a5f-7f438ef7ca01" />
