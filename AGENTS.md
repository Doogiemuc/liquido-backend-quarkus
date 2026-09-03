# agents.md

## Overview

This project is a backend service built with **Quarkus**, designed to support a mobile application. It exposes a **GraphQL API** and uses **Panache ORM (Hibernate with Quarkus extensions)** for persistence. The system models users, teams, polls, delegations, and voting logic, including advanced voting algorithms.

---

## Core Technologies

### Runtime & Framework
- **Quarkus**
    - Cloud-native Java framework optimized for fast startup and low memory usage
    - Supports imperative and reactive programming models

### API Layer
- **SmallRye GraphQL (MicroProfile GraphQL)**
    - GraphQL endpoints implemented via classes like:
        - `UserGraphQL`
        - `TeamGraphQL`
        - `PollsGraphQL`
        - `DelegationGraphQL`
    - Schema-first approach inferred from annotated Java classes

### Persistence Layer
- **Hibernate ORM with Panache**
    - Simplified active-record pattern
    - Entities suchs as:
        - `UserEntity`
        - `TeamEntity`
        - `PollEntity`
        - `DelegationEntity`
        - `BallotEntity`
    - Backed by a relational database: PostgreSQL

---

## Security & Authentication

### Authentication Mechanisms
- **JWT (JSON Web Tokens)**
    - Managed via `JwtTokenUtils`
    - Used for stateless authentication

- **Google Login Integration**
    - Implemented in `GoogleLogin`

- **WebAuthn (Passwordless Authentication)**
    - Classes under `security.webauthn`
    - Supports hardware/security key authentication

- **One-Time Tokens**
    - `PasswordResetToken` (table `password_reset_tokens`) — allows a single passwordless login or a password reset. Deleted after use, with a limited TTL.
    - `OneTimeVotingToken` (table `voting_tokens`) — grants a voter the right to cast exactly **one** vote in a poll. Deleted once consumed. Stores only a hash of the voter token.

### Password Security
- **BCrypt**
    - Implemented via `PasswordServiceBcrypt`

---

## Communication & External Services

- **Twilio Verify API**
    - Integrated via `TwilioVerifyClient`
    - Used for phone verification / OTP delivery

---

## Domain Modules

### User Management
- `UserService`, `UserEntity`
- Handles user lifecycle and authentication context

### Team Management
- `TeamEntity`, `TeamMemberEntity`
- GraphQL access via `TeamGraphQL`

### Polling & Voting
- `PollEntity`, `ProposalEntity`
- Voting handled by:
    - `CastVoteService`
    - `BallotEntity`

### Voting Algorithms
- Custom implementations:
    - `RankedPairVoting`
    - `ComparisonComparator` — orders `RankedPairVoting.Comparison` records: more winner votes first, then fewer loser votes
    - `DirectedGraph`
    - `Matrix`
- Implement a ranked-choice or Condorcet-style voting system

### Delegation System
- Liquid democracy concepts:
    - `DelegationEntity`
    - `DelegationService`
    - GraphQL interface

---

## Liquid Democracy Voting Process

The voting process in this system is designed to support liquid democracy principles, combining direct voting with delegation.

1.  **Poll Creation**: A `PollEntity` is created with a set of `ProposalEntity` options.
2.  **Delegation**: Users can delegate their vote to another user for a specific topic or for all polls. This is managed by `DelegationEntity` and `DelegationService`. When a user delegates their vote, their chosen delegate can cast a vote on their behalf.
3.  **Casting Votes**:
    *   **Direct Vote**: A user can directly cast their vote on a poll using the `CastVoteService`, which creates a `BallotEntity`. Votes can be ranked (e.g., 1st choice, 2nd choice).
    *   **Delegated Vote**: If a user has delegated their vote, and the delegate casts a vote, that vote is counted for both the delegate and the delegator. The system ensures that each user's vote is counted only once, either directly or via delegation.
4.  **Vote Counting and Outcome Determination**:
    *   Once a poll closes, the `CastVoteService` aggregates all `BallotEntity` instances.
    *   The system then applies Condorcet voting algorithms (in `RankedPairVoting` with `ComparisonComparator`) to determine the winning proposal. These algorithms are designed to handle ranked-choice voting and Condorcet methods, ensuring a fair and robust outcome based on the collective preferences expressed through direct and delegated votes.
    *   The `DirectedGraph` and `Matrix` utilities assist in the complex calculations required by these algorithms.

---

## Utilities & Infrastructure

- **Custom Exception Handling**
    - `LiquidoException`
    - GraphQL error extensions via `LiquidoErrorExtensionProvider`

- **Logging**
    - `LiquidoRequestLogger`

- **Configuration**
    - `LiquidoConfig`

- **Serialization Utilities**
    - `Lson` (likely custom JSON handling)

---

## Building & Running

### Toolchain
- **Java 26** (`maven.compiler.release=26` in `pom.xml`)
- **Quarkus 3.37.4 or newer is REQUIRED for Java 26.** Older versions bundle an ASM that cannot read class file major version 70 and fail augmentation with `Unsupported class file major version 70`.
- **Lombok** — two things must both hold, or every generated getter/setter/constructor fails with `cannot find symbol`:
    1. Lombok must be recent enough for the JDK in use. It patches javac internals, so an old Lombok crashes or silently generates nothing on a newer JDK.
    2. Lombok must be declared in `<annotationProcessorPaths>` of `maven-compiler-plugin`. Since **JDK 23** javac no longer discovers annotation processors on the compile classpath, so a plain dependency alone is not enough.
- **IntelliJ** — the Lombok plugin has been bundled since IDEA 2020.3, so nothing needs installing. `.idea/externalDependencies.xml` and `.idea/compiler.xml` are committed so a fresh clone gets the plugin prompt and annotation processing already enabled.

### Local database
- PostgreSQL on `localhost:5432`. **Two** databases, one per profile:
  - **`LIQUIDO-DEV`** — what `./mvnw quarkus:dev` serves, i.e. what you browse and demo from.
  - **`LIQUIDO-TEST`** — what `./mvnw test` uses. All test residue lands here.
- Create them with the name quoted — unquoted, Postgres folds it to lowercase and the hyphen is a syntax error:
  ```sql
  CREATE DATABASE "LIQUIDO-DEV" OWNER postgres;
  CREATE DATABASE "LIQUIDO-TEST" OWNER postgres;
  ```
- Datasource settings live in `config/application-{dev,test}.properties`. These are **gitignored** because they contain secrets, so a fresh clone must supply them.
- These two used to be the **same** database (until 2026-08-14), which meant `mvn test` wrote into the data you demo from, every run left residue there, and enabling `drop-and-create` for the test profile destroyed dev data outright. If you ever point them back at one database, all three problems return.
- Keep `liquido.hash-secret` **identical** across the two profiles. It keys `RightToVoteEntity` (`HMAC-SHA256(secret, email | teamId)`) and, through it, every ballot pseudonym, so a mismatch means ballots created under one profile cannot be found under the other — see the "two different salts" story in `docs/liquido-vibe-coding-stories.md`.
- **Seeding is now per database:**
  - `LIQUIDO-TEST` — run the generator (it uses the test profile); see "Schema and seed data" below.
  - `LIQUIDO-DEV` — restore the dump as superuser: `psql -h localhost -U postgres -d "LIQUIDO-DEV" -f liquido-testData.sql` against a freshly drop-and-created schema.

### Schema and seed data
- Schema generation is deliberately **off** (`quarkus.hibernate-orm.database.generation=none` and `quarkus.hibernate-orm.schema-management.strategy=none`). Both the legacy and the newer key are set.
- To create the schema once in an empty DB, override via environment rather than editing the config files:
  ```
  QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=drop-and-create
  ```
- Seed data comes from `TestDataCreator.createTestData()`, which is `@Disabled` **on purpose** — it is meant to be run by hand, never as part of a normal build. Do not enable it in the build.
- Run the E2E generator rarely (when the happy-path itself changes) to refresh `liquido-testData.sql`, then just `psql < liquido-testData.sql` against a freshly drop-and-created schema for everyday reseeding — no JVM/Quarkus boot, no HTTP round-trips. Restoring needs a **superuser** (`postgres`), because the dump disables triggers — see below.
- **The dump must be produced with `--disable-triggers`, and that is not cosmetic.** This schema has circular foreign keys: `polls.winner_id → proposals` while `proposals.poll_id → polls`, the same shape between `polly` and `polly_proposal`, plus self-referencing `righttovote`. A `--data-only` dump writes tables in one flat order and no order can satisfy a cycle, so a dump taken without the flag is **silently unrestorable** — `pg_dump` succeeds, warns on stderr, and the failure only appears later as `insert or update on table "polls" violates foreign key constraint`. `TestDataCreator.extractPostgresData()` passes the flag; don't remove it.
- `liquido-testData.sql` is **gitignored** (`.gitignore:67`), so it does not travel between machines or reach CI. Regenerate it locally after any schema or seed change.

### Commands
```bash
./mvnw quarkus:dev          # dev mode
./mvnw clean test           # unit + integration tests (needs a seeded LIQUIDO-TEST)
./mvnw clean package        # build target/quarkus-app/
```

---

## Testing

- JUnit-based test suite under `src/test/java`
- Covers:
    - Voting algorithms
    - Authentication
    - Use cases and integration flows
- Integration tests need a running, seeded `LIQUIDO-TEST`. Without it Quarkus cannot boot and the tests error rather than fail.
- A handful of tests are `@Disabled` by design (`TestDataCreator`, and two in `AuthenticationTests` that only work in manual debug runs). Leave them disabled.
- `skipITs` defaults to `true`, so failsafe integration tests do not run in a normal build.

### `TestDataCreator.java` is both the seed generator *and* the happy-path E2E test

`TestDataCreator.createTestData()` is not a "test data fixture" in the usual sense — it is a single test method that walks through the **entire real-world use case** using the exact same sequence of GraphQL calls the mobile app would send: register admin → member joins team → create polls/proposals → start voting → cast votes → verify ballot → finish voting phase → check winner. Running it both *exercises the full happy path* and *is* the seeding mechanism. One method, two jobs, by design.

Consequences, all deliberate trade-offs for speed over textbook test hygiene:

- **Other tests are not atomic or independent.** Many tests (`UseCaseTests`, `AuthenticationTests`, etc.) assume specific pre-existing data this method creates: a team named `testTeam4711`, an admin `testadmin4711@liquido.vote`, a fixed set of members, polls in various states (ELABORATION, VOTING, FINISHED). This is intentional — re-deriving that whole scenario per test would be much slower. If you add a test that needs its own team, do **not** assume you can just call `TestDataCreator.createTestData()` again or reuse `LiquidoTestUtils.createTeam()` a second time: that helper hardcodes a mobile-phone number tied to the fixed seed constants and will collide. Use `LiquidoTestUtils.createFreshTeam(prefix)` instead (timestamp-based, safe to call any number of times) if a test needs an isolated team rather than the shared seeded one.
- **`TeamEntity.members` is an unordered `java.util.HashSet`.** Never assume `team.getMembers().stream().toList().get(0)` is "the admin" or "the first member" — use explicit role/email filters (see `proxyCastsVoteForVoter` for the pattern), or better, use a dedicated fresh team so ordering doesn't matter at all.
- **`@TestTransaction` does not roll back HTTP-triggered mutations.** Tests call the running server over real HTTP (RestAssured against `localhost:8081`), which runs on its own thread/transaction and commits independently of the test method's own `@TestTransaction` wrapper. That annotation only rolls back the test method's *own direct* Panache calls. Practical fallout: (a) any test that adds members/polls/delegations to the **shared seeded team** via an HTTP call leaves that data behind permanently for every later test in the same run — prefer an isolated fresh team instead; (b) if a test needs to directly persist something via JPA (e.g. `RightToVoteEntity.setPublicProxy(...)`) *and then* make an HTTP call that must see that change, the direct write must be committed in its own transaction first (`io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(...)`), since it would otherwise still be uncommitted and invisible to the separate HTTP-request transaction.
- **Precondition changes must be documented and kept in sync.** Since so much depends on this one method's exact output, any change to `createTestData()` (new poll, changed proposal count, etc.) can silently break unrelated tests elsewhere that count on the old shape. Grep for the specific IDs/emails/titles it creates before changing them.

#### The seed contract (added 2026-08-14)

The point of the shared seed is that **extending it must be harmless**. Two rules make that true, and `SeedContractTests` asserts them:

1. **APPEND freely.** A test may add polls, proposals, likes, ballots and its own voter tokens to the seed team. Nothing may depend on the seed team's exact counts.
2. **Never change the identity or relationships of seed rows** — delegations, team membership, passwords, `lastTeamId`. Those alter other tests' *preconditions* rather than adding to them, and `@TestTransaction` won't undo them (see above). Use `createFreshTeam(prefix)`; `UseCaseTests.proxyCastsVoteForVoter` is the worked example — it used to delegate inside the seed team, permanently making the seed admin a proxy.

And on the reading side: **ask for the row you mean, by name.** `util.getSeedTeam()` / `getSeedAdmin()` / `getSeedMember()` / `getSeedTeamMember()`, plus `getAnyUser()` for tests that need *a* user and assert nothing about which. These replaced `getRandomTeam/Admin/User()`, which were `findAll().firstResultOptional()` with no `ORDER BY` — not random, just "first row of an unordered scan", which Postgres is free to change whenever a row is UPDATEd (and every login updates one). That is what made leftover throwaway teams able to break unrelated tests. Anchoring by name is *why* nothing cleans the database up: nothing reads the garbage.

Two traps worth knowing before you extend the seed:

- **`devLogin` logs a user into their `lastTeamId`**, which `joinTeam` rewrites. So a user in two teams logs into whichever they joined last, and every team-scoped call afterwards fails with `Poll(id=…) not found` — intermittently, since `seedRandomProposals` picks authors positionally. Use `devLoginInto(email, teamId)` to pin the team. `seedRandomProposals` pins, and additionally asserts the session came back scoped to the right team.
- **Assert only over your own data.** `UseCaseTests` used to assert the *whole* `voting_tokens` table was empty; one abandoned token anywhere broke it permanently and blamed the wrong test.

Manual reseed procedure (never done automatically, never in a normal build):
1. Stop `quarkus:dev` first — it holds the old schema, and `clean` pulls `target/` out from under it.
2. ```
   QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=drop-and-create \
   QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION=drop-and-create \
   ./mvnw -B test -Dmaven.surefire.includedGroups=testDataCreator -Dmaven.surefire.excludedGroups=""
   ```
   **Both** env vars are needed; the legacy `database.generation` key in `application.properties`
   otherwise silently wins over the newer `schema-management.strategy` key and no schema gets created.
3. Run the full suite normally (`./mvnw -B clean test`, no env vars) to confirm a clean baseline.

No source edit is needed to enable or re-disable the generator. `createTestData()` is **not** `@Disabled`
any more — `TestDataCreator` carries `@Tag("testDataCreator")` and `pom.xml` sets
`maven.surefire.excludedGroups=testDataCreator`, so it is skipped by every normal build and opted into
with the two `-D` flags above. The class documents this on its own `@Tag` line.

A fresh seed is small — roughly 10 users / 3 teams / 7 polls / 25 proposals / 3 ballots, and one right to
vote per team MEMBERSHIP (11 of them, not one per user). The extra two teams are the multi-team scenario
in `createMultiTeamMemberWhoVotesInSecondTeam()`; older revisions of this file said 7/1/5/20/2, which
predates that scenario and made a clean seed look like accumulated residue.
If a long-lived dev DB shows far more than that, it is accumulated test residue, not the seed: the
happy-path e2e and every `createFreshTeam(prefix)` call leave their teams behind on purpose.

---

## Security Assets

- TLS/SSL certificates and keys in `resources`
- JWT signing: RS256 keypair per environment, injected via `config/application-<profile>.properties` (gitignored) or env vars for fly.io. No key file is committed — `src/main/resources/liquidoJwtKey.json` (a symmetric HS256 secret) used to be tracked in git and was purged after rotation.

---

## Architectural Notes

- Follows a **layered architecture**:
    - GraphQL API → Services → Entities (Panache)
- Strong domain modeling around voting and delegation
- Mix of synchronous service logic and stateless API design
- Emphasis on modern authentication (JWT + WebAuthn)

### Deeper architectural learnings (from the 2026-07-30 security remediation)

- **Team is the tenant boundary, and it's not automatic.** Every poll/proposal/ballot belongs to exactly one team, but `@RolesAllowed(LIQUIDO_ADMIN_ROLE)` only checks that the caller is *an* admin of *some* team — never that they own the specific resource being touched. Ownership has to be checked explicitly and separately from the role check. `JwtTokenUtils.getCurrentTeam()` (backed by the `teamId` JWT claim) is the one canonical way to get "the caller's team" for that comparison; `PollService.getPollInCurrentTeam(pollId)` is the pattern to follow for any new poll-scoped lookup — one combined lookup+ownership-check call, so a call site can't do the lookup and forget the guard.
- **Ballot anonymity is now a THREE-layer derivation, and the scopes are the point.** (Reworked 2026-09-03; this section previously described a two-hop chain with a database FK from ballot to right-to-vote, which no longer exists.)
  1. `hashedVoterInfo = HMAC-SHA256(secret[v], email | teamId)` — persistent, scoped to **one team**, the `@Id` of `RightToVoteEntity`. Carries eligibility, expiry and the delegation graph. A person in three teams holds three unrelated rights to vote, so two unrelated LIQUIDO instances cannot correlate their members even with both databases.
  2. The one-time voter token — hashed, poll-bound, 20 minutes, at most **one live per (voter, poll)**, deleted on use.
  3. `ballotPseudonym = HMAC-SHA256(secret[v], hashedVoterInfo | pollId)` — ephemeral, scoped to **one poll**, and the only voter-derived value a ballot stores. **`ballots` has no foreign key to `righttovote` at all.** The mapping is never persisted; the server re-derives it on demand.
  Rules that still hold: never include anything mutable (like `passwordHash`) in layer 1, or a password change orphans ballots; never log `hashedVoterInfo` or a pseudonym. New rule: a right to vote is granted per **membership** (founding or joining a team), never at registration — an account is not by itself an entitlement to vote anywhere.
- **A cast vote is final.** `castVoteRec` rejects a second *direct* (level 0) cast with `ALREADY_VOTED`. The check is deliberately scoped to level 0 on **both** sides: level > 0 ballots are a proxy's cascade, and must still be replaceable by a closer proxy, by the delegee's own first direct vote, or by a new proxy after a re-delegation. Widening it to "any equal level" silently breaks re-delegation — `CastVoteOverrideTest` exists to catch exactly that.
- **Delegation's cardinality is asymmetric, and the mapping annotations must say so.** A voter has at most one proxy (`DelegationEntity.fromUser` is correctly unique), but a proxy can have unlimited delegees (`toProxy` must be `@ManyToOne`, not `@OneToOne` — the latter silently caps every proxy in the whole system to one delegee via an auto-generated unique constraint on the FK column, and nothing fails loudly when that cap is hit for the first user, only for the second).
- **The same `@OneToOne` bug bit a second time, in `TeamMemberEntity.user` (fixed 2026-08-13).** A user has one `TeamMemberEntity` row *per team*, so it must be `@ManyToOne`. As `@OneToOne` it generated `UNIQUE(user_id)` on `team_members`, capping every user at exactly one team membership system-wide. Joining a *first* team worked, so nothing looked broken; joining a *second* one failed at commit with a raw `ConstraintViolationException` surfaced as an opaque `INTERNAL_ERROR` — pointing at neither the mapping nor the real cause. `findTeamsByMember()` returning `List<TeamEntity>` had always shown the intent; only the annotation disagreed. **When you see `@OneToOne` on a join entity in this codebase, treat it as suspect until proven otherwise** — all three known instances were wrong, and each stayed invisible until the second row was inserted.
- **And a third time, in `RightToVoteEntity.publicProxy` (2026-09-03).** Harmless while a user held exactly one right to vote; the moment rights to vote became per-team, one user could legitimately be a public proxy in several teams, and `@OneToOne` would have generated `UNIQUE(publicproxy_id)` capping them at one. Caught while making that change *because* this list already recorded the pattern twice — which is the whole reason to write these down.
- **The GraphQL layer is meant to be a thin adapter.** `PollsGraphQL`, `DelegationGraphQL` etc. should just parse input and delegate to the matching `*Service` class, which owns all the actual invariants. Nearly every security bug found this session was a GraphQL resolver doing its own ad hoc entity lookup instead of calling an already-correct service helper that sat a few lines away.
- **No migration tool yet.** Schema is whatever the current entity annotations produce via `drop-and-create`; there's no Flyway baseline. This means an entity-mapping bug (like the `@OneToOne`/`@ManyToOne` one above) can lurk indefinitely in a long-lived database that was never regenerated, and won't surface until someone does a fresh `drop-and-create` or until enough concurrent users hit the hidden constraint.
- **HQL/JPQL gotcha:** comparing an entity-valued (association) field to `null` with `!=` (e.g. `requestedDelegationFrom != null`) silently matched zero rows in this Hibernate version, even though the same rows are found fine with `is not null`. Prefer `is not null` / `is null` for association fields, not `!=`/`=`.