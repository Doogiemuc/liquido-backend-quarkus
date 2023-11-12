# LIQUIDO - Technical Reference

# CHANGES

 * 2023-06-23 GREEN Test with complete Happy Case
 * 2023-07-04 Upgraded to Quarkus 3.1.3.Final
 * 2023-07-04 Working on native build
 * 2023-07-24 CreatedBy is now automatically added in Entities

TODO:

 * https://stackoverflow.com/questions/56871033/how-to-fix-org-apache-commons-logging-impl-logfactoryimpl-not-found-in-native

# TESTING

The first time you run `TestDataCreator` you must drop-and-create the database! All
future tests will rely on this data.

MAYBE: implement a maven pre-test-execution

# DATABASE

### ORM Database mapping

We use Quarkus-Panache
https://quarkus.io/guides/hibernate-orm-panache



----



# FURTHER LINKS & REFERENCES

### Changes to the Spring version of the backend

 * New entity TeamMember
 * JWT contains email as subject instead of user.id



### GraphQL Links & References

Very nice Repo with very good example:
https://github.com/hantsy/quarkus-sandbox/tree/master/graphql


### Quarkus Smallrye GraphQL with JWT

Medium Article with code examples
https://ard333.medium.com/authentication-and-authorization-using-jwt-on-quarkus-aca1f844996a

Get custom user from Security Context via custom AuthenticationContextImpl
https://stackoverflow.com/questions/66695265/how-to-retrieve-securitycontext-in-a-quarkus-application

### WebAuthn - Passwordless login

Checkout this lib. This looks promising. Nice article.
https://developers.yubico.com/java-webauthn-server/

Nice easy simple sequence diagram for webauthn registration and authentication:
https://passwordless.id/protocols/webauthn/2_registration

https://webauthn.io
https://webauthn.guide

Nice funny written article
https://medium.com/digitalfrontiers/webauthn-with-spring-security-c9175aae3e06

There is a Quarkus Lib. But that forces us to use reactive DB access. Which conflicts with our DB access.



# TODO

Checkout all the //TODO annotations in the code

Checkout Quarkus SimpleScheduler for cleanup operations in the background. 