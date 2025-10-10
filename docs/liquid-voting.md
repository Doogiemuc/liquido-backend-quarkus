# LIQUIDO - Liquid Voting



## Requirements

### Voting

 - Every vote must be free, secret, anonymous, equal and direct!
 - A voter is registred in a voter directory ("members of a team")
 - In the digital world a voter's **right to vote** is a **digitally signed key**.
 - When a voter wants to cast a vote in a poll, then he fetches a one time token for this poll with his right to vote key.  
 - Then he can cast an anonymous ballot with this one time token.

### Delegate your right to vote

 - A voter may decide to delegate his right to vote to a **proxy**. Then the proxy can vote for him.
 - This can be transitive. A proxy may in turn delegate his right to vote and all his transitively delegated RTV to yet another proxy.


### Security chain

 1. Authenticated user requests a right to vote ("Wahlschein") in an area.
    (This right to vote may then be delegated to a proxy.)
 2. User wants to cast a vote in a poll.
 3. User requests one time token for this poll.
 4. Voter can anonymously cast a vote with this OTT.


### Use Case Flow

Voter creates random userSecret and sends it to the backend.

Backend calculates two hashes:

sha3_256(userId + userSecret + backendSecret) = voterToken       
sha3_256(pollId + voterToken + backendSecret) = one time password to vote in this poll

The voterToken is returned to the user. With this token he can check and prove that this is his vote.
The one time password


sha3_256(userId + userSecret + pollId + backendSecret) = one time password for this user to vote in this poll