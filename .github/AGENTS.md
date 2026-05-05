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
    - `OneTimeToken` for temporary authentication flows

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
    - `MajorityComparator`
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
    *   The system then applies condocrete voting algorithms (in `RankedPairVoting` with `MajorityComparator`) to determine the winning proposal. These algorithms are designed to handle ranked-choice voting and Condorcet methods, ensuring a fair and robust outcome based on the collective preferences expressed through direct and delegated votes.
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

## Testing

- JUnit-based test suite under `src/test/java`
- Covers:
    - Voting algorithms
    - Authentication
    - Use cases and integration flows

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