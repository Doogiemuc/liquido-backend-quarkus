# LIQUIDO - Liquid Voting

Voting in LIQUIDO is designed to be anonymous, verifiable, and compatible with proxy delegation.

To participate in a poll, a voter first receives a one-time voting token for that specific poll. This token proves that the person is allowed to vote, but the vote itself is stored without a direct link to the voter’s identity. The token can only be used once and expires after a limited time.
When a vote is submitted, the system checks that the poll is currently open for voting and that the ranking only contains valid options from that poll, without duplicates. Once the token has been accepted, it is consumed and cannot be reused.
Each vote is stored as a ranked ballot. Voters can rank some or all proposals in their preferred order. As long as the poll remains open, an existing ballot can be replaced by a newer one from the same voter.
Proxy delegation works by allowing a voter to delegate their voting right to another person. If that proxy casts a vote, the system automatically applies the same ranked ballot not only to the proxy’s own vote, but also to the votes of all people who have delegated to them, including transitive delegations. This means delegation chains are supported.
At the same time, direct participation always has priority. If a voter has already cast their own ballot, or if a closer proxy in the delegation chain has already voted on their behalf, a more distant proxy cannot overwrite that decision. This ensures that delegation is a fallback mechanism, not a loss of control.
In practice, this means:

 * You can vote yourself at any time while the poll is open.
 * You can update your vote while voting is still open.
 * If you do not vote yourself, your delegated proxy can vote for you.
 * Your own vote always overrides proxy voting on your behalf.

This approach combines personal control, anonymous voting, and scalable proxy delegation in a way that is consistent with the principles of liquid democracy.

# Requirements

### Secure, free, anonmyous Voting

 - Every vote must be free, secret, anonymous, equal and direct ... and LIQUID
 - A voter is registered in a voter directory ("members of a team")
 - A voter needs a right to vote to be able to anonymously cast any vote.
 - When a voter wants to cast a vote in one poll, then he must request a one time voterToken.
 - A voter can, of course, only vote once in a poll.
 - One ballot is created for every cast vote.

### Delegate your right to vote

 - A voter may decide to delegate his right to vote to a **proxy**. Then the proxy can vote for him.
 - This can be transitive. A proxy may in turn delegate his right to vote and all his transitively delegated RTV to yet another proxy.
 - When a proxy casts a vote, then one ballot will be stored for himself and further ballots for his delegations.
 - A voter can always vote for himself. Even after his proxy has voted for him.
 - Then the voter's ballot overwrites the previously cast ballot of the proxy.

But every vote is cast anonymously. A voter only sends the anonymous voterToken. To find a previous ballot 
that we need to overwrite, the backend uses this chain:

### Security chain

1. When a user registers, a RightToVote is generated. 
2. When a user wants to cast a vote then he fetches a one time voter token from the backend.
   The backend calculates the hashedVoter token (including some more attributes for enhanced security)
   and then stores this VoterTokenEntity. 
3. When a voter wants to cast a vote in one poll, then he must request a one time voterToken. 
4. VoterTokens are completely anonymous. They are not linked back to the voter in any way.  VoterTokens are only valid for a short time. 
5. Then the voter can anonymously cast a vote by sending this anonymous voterToken and his preferred vote order for this poll.
6. One ballot is created for every cast vote.
7. The VoterToken is linked to a RightToVote that might have delegations. If so, then further ballots are recursively created 
   for these voters that delegated their vote to that proxy.

### Use Case Flow

1.	Token Request:
    •	User (logged in) asks for a token for a given poll
    •	Server generates a secure random token (UUID or HMAC(secret, userId + pollId))
    •	Stores only its hash in the DB (e.g., SHA-256(token))
    •	Returns the raw token to the client (never stored)

2.	Voting:
   •	User (now logged out or anonymous) submits: his (raw) token and his chosen voteOrder
   •	Server hashes the token, checks for, Matching hashedToken in DB, Same poll and token was not used yet
   •	Marks it as used
   •	Accepts the vote and creates a ballot


# Open questions

Shall poll results be available, before a poll has been finished?   => NO
Shall a voter be able to **change** his vote, as long as a poll is running?  => NO

Shall voters be able to verify that their vote has been cast and counted correctly? => Defenitely YES 

Shall a voter be able to receive his **own(!)** ballot, incl. the vote order he has cast.
   This would even be possible in LIQUIDO.
   voter -> hash(voter.id + ...) -> RightToVote -> Ballot in poll