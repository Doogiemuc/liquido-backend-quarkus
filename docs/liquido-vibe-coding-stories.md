# Two different salts

After quite a long time of only frontend coding with the mocked backend, I started with full end-2-end testing again. The story started when I added the GraphQL boolean attribute "userAlreadyVoted". Just a simple new GraphQl method with a @Source in the backend. But as it turns out, this never found ballots.

The algo in my backend to find cast ballots of the currently logged-in user is cryptographically secured. It is only possible to look up the ballot of a voter. Not the other way round. And only if a secret "salt" is known on the server. I searched for a few hours why the hashedUserInfo doesn't match. Until I found out that I configured a different salt in `application-test.properties`, which is used in my famous TestDataCreator, than in `application-dev.properties`, which is used during standard `mvn quarkus:dev`.

### What did I learn?

Log a lot. Debugging becomes harder and harder in bigger applications. (e.g. because of network timeouts you have to debug "fast" :-)
If you change anything, no matter how little, then always run regression tests.

# Test Data Creator

Whuuhuu once again my famous `TestDataCreator.java`. It's two things at the same time. It's one large test case that runs through my full end-2-end use case flow. 
And admin creates a Team. Then a second team member joins that team. Admin creates a new poll with two proposals. Then both vote. etc. 









# Nice AI bugfix: `!= null` is not the same as `is not null` in Panache

Root cause found: DelegationEntity.findDelegationRequestsTo uses HQL requestedDelegationFrom != null, which apparently doesn't work for entity-valued fields in this Hibernate version — is not null returns all 8 rows correctly, != null returns 0.