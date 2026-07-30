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
- PostgreSQL on `localhost:5432`, database **`LIQUIDO-DEV`**.
- Create it with the name quoted — unquoted, Postgres folds it to lowercase and the hyphen is a syntax error:
  ```sql
  CREATE DATABASE "LIQUIDO-DEV" OWNER postgres;
  ```
- Datasource settings live in `config/application-{dev,test}.properties`. These are **gitignored** because they contain secrets, so a fresh clone must supply them.
- Note that the `dev` and `test` profiles currently point at the **same** database. Enabling `drop-and-create` for the test profile therefore destroys dev data on every `mvn test`.

### Schema and seed data
- Schema generation is deliberately **off** (`quarkus.hibernate-orm.database.generation=none` and `quarkus.hibernate-orm.schema-management.strategy=none`). Both the legacy and the newer key are set.
- To create the schema once in an empty DB, override via environment rather than editing the config files:
  ```
  QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=drop-and-create
  ```
- Seed data comes from `TestDataCreator.createTestData()`, which is `@Disabled` **on purpose** — it is meant to be run by hand, never as part of a normal build. Do not enable it in the build.

### Commands
```bash
./mvnw quarkus:dev          # dev mode
./mvnw clean test           # unit + integration tests (needs a seeded LIQUIDO-DEV)
./mvnw clean package        # build target/quarkus-app/
```

---

## Testing

- JUnit-based test suite under `src/test/java`
- Covers:
    - Voting algorithms
    - Authentication
    - Use cases and integration flows
- Integration tests need a running, seeded `LIQUIDO-DEV`. Without it Quarkus cannot boot and the tests error rather than fail.
- A handful of tests are `@Disabled` by design (`TestDataCreator`, and two in `AuthenticationTests` that only work in manual debug runs). Leave them disabled.
- `skipITs` defaults to `true`, so failsafe integration tests do not run in a normal build.

---

## Security Assets

- TLS/SSL certificates and keys in `resources`
- JWT key material (`liquidoJwtKey.json`)

---

## Architectural Notes

- Follows a **layered architecture**:
    - GraphQL API → Services → Entities (Panache)
- Strong domain modeling around voting and delegation
- Mix of synchronous service logic and stateless API design
- Emphasis on modern authentication (JWT + WebAuthn)