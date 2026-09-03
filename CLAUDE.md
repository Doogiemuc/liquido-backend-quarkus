# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This repo also has an `AGENTS.md` with extensive detail — read it for anything not covered here,
especially the "Deeper architectural learnings" and seed-data sections. Do not duplicate its content
into memory; it is kept current in the repo itself.

## What this is

Java/Quarkus backend for LIQUIDO (liquido.vote), a liquid-democracy eVoting app. GraphQL API
(SmallRye/MicroProfile GraphQL, schema-first from annotated Java classes) over Hibernate ORM with
Panache, backed by PostgreSQL. Serves a separate mobile app frontend.

## Commands

```bash
./mvnw quarkus:dev          # dev mode, live reload, serves LIQUIDO-DEV, Dev UI at /q/dev/
./mvnw clean test           # unit + integration tests, needs a seeded LIQUIDO-TEST
./mvnw clean package        # build target/quarkus-app/
```

Run a single test class or method (standard surefire syntax):
```bash
./mvnw test -Dtest=UseCaseTests
./mvnw test -Dtest=UseCaseTests#proxyCastsVoteForVoter
```

- Java 26, Quarkus 3.37.4+ (older Quarkus can't read Java 26 class files — see `pom.xml` comment).
- `skipITs=true` by default, so failsafe integration tests don't run in a normal build.
- `TestDataCreator` is tagged `testDataCreator` and excluded from normal test runs
  (`maven.surefire.excludedGroups`). It is both the seed generator *and* the happy-path E2E test —
  see AGENTS.md before touching it or re-running it against a real database.

## Local database

Two separate Postgres databases are required — **do not point both profiles at the same database**
(see [[local-dev-database]] and AGENTS.md for why this caused real data loss before):
- `LIQUIDO-DEV` — served by `quarkus:dev`, seeded manually via `psql -f liquido-testData.sql`
- `LIQUIDO-TEST` — used by `./mvnw test`, seeded by running `TestDataCreator` with the
  `testDataCreator` group enabled

Schema generation is off by default (`schema-management.strategy=none`). Never uncomment
drop-and-create in the checked-in `application*.properties` files — every profile loads every
properties file, so it would wipe `LIQUIDO-DEV` on the next `quarkus:dev` run. Enable it only via
the `QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY` env var for a one-off reseed, per AGENTS.md.

`config/application-{dev,test}.properties` are gitignored (contain secrets/datasource creds) — a
fresh clone must supply them.

## Architecture

Layered: **GraphQL resolvers → Services → Panache entities**. Resolvers (`*GraphQL` classes, one
per domain area: `UserGraphQL`, `TeamGraphQL`, `PollsGraphQL`, `DelegationGraphQL`) are meant to be
thin — parse input and delegate to the matching `*Service`, which owns the actual invariants
(ownership checks, state transitions). Most historical bugs here were a resolver doing its own ad
hoc entity lookup instead of calling an already-correct service helper.

Package layout under `src/main/java/org/liquido/`: `user`, `team`, `poll` (polls/proposals/voting),
`polly` (a separate simpler poll type), `delegation` (liquid-democracy proxy chains), `vote`
(ballot casting, `RankedPairVoting`/Condorcet counting), `security` (JWT, WebAuthn, Google login),
`twillio` (phone OTP), `model`, `tools`, `util`.

Key invariants worth knowing before changing poll/vote/team code (full detail in AGENTS.md):
- **Team is the tenant boundary**, and `@RolesAllowed` alone does not check resource ownership —
  use `JwtTokenUtils.getCurrentTeam()` plus a combined lookup+ownership-check helper like
  `PollService.getPollInCurrentTeam(pollId)`.
- **Ballot anonymity** is three derived layers with three different scopes:
  `RightToVoteEntity` keyed by `HMAC(secret, email | teamId)` (persistent, **per team**, holds the
  delegation graph) → a one-time poll-bound voter token → `ballotPseudonym = HMAC(secret,
  hashedVoterInfo | pollId)` (**per poll**), which is the only voter-derived value a ballot stores.
  `ballots` has **no FK to `righttovote`**. Never log `hashedVoterInfo` or a pseudonym.
  A right to vote is granted per team MEMBERSHIP, not at registration.
- **A cast vote is final** — a second *direct* (level 0) cast is rejected with `ALREADY_VOTED`.
  Scoped to level 0 on both sides on purpose; level > 0 is a proxy cascade and must stay replaceable.
- **Any `@OneToOne` on a join entity is suspect** — three real bugs here were a join that should have
  been `@ManyToOne` silently capping cardinality at 1, invisible until a second row was inserted.
- Use `is not null` / `is null` in HQL/JPQL for association fields, not `!=`/`=null` — the latter
  silently matches zero rows on this Hibernate version.

## Test data / fixtures

Most integration tests depend on the shared seed team (`testTeam4711`) created by
`TestDataCreator`. Rules for extending it safely (enforced by `SeedContractTests`):
1. Append freely (new polls/proposals/ballots) — never assert on exact seed counts.
2. Never change identity/relationships of existing seed rows (delegations, team membership,
   passwords) — `@TestTransaction` does not roll back HTTP-triggered mutations, so this leaks into
   later tests. Use `LiquidoTestUtils.createFreshTeam(prefix)` for anything that needs isolation.

Read rows by name, not position: `LiquidoTestUtils.getSeedTeam()/getSeedAdmin()/getSeedMember()`.
`TeamEntity.members` is an unordered `HashSet` — never index into it.
