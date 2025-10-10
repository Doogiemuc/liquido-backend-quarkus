# LIQUIDO - Technical Reference

See also the LIQUIDO theses for a more in depth look into the theoretical backgroudn of liquid democracy.

# CHANGELOG (only most important new features)

 * 2023-06-23 GREEN Test with complete Happy Case
 * 2023-07-04 Upgraded to Quarkus 3.1.3.Final
 * 2023-07-04 Working on native build
 * 2023-07-24 CreatedBy is now automatically added in Entities
 * 2023-11-01 Added TLS for backend HTTPS
 * 2023-11-22 Even more work on UI layout. It's starting to look smooth.
 * 2023-12-24 Debugging WebAuthn since 3 weeks :-( No luck.
 * 2025-03-18 Can now build docker container - with help of AI
 * 2025-05-08 Added Passwords for users.
 * 2025-05-24 Found drastic issue with RightToVote, when voting in more thant one poll!
 * 2025-07-10 Fixed issue with RightToVote by introducing a new VoterToken entity.
 * 2025-07-10 Worked on username password login in backend.

# Next TODOs

 * Native Build
 * Checkout all the //TODO annotations in the code 
 * Checkout Quarkus SimpleScheduler for cleanup operations in the background.
 * Fix bug so that user can vote more than once.  Ballot <-> Poll
 * Make sure that a "human" can only register once. (as good as this is technically feasable. -> mobile numbers?)
 * Prevent loops in delegation chain!



# DATABASE

### ORM Database mapping

We use Quarkus-Panache
https://quarkus.io/guides/hibernate-orm-panache

### Initialize the database

On Startup liquido checks for the most important tables. You **have to** create a DB schema first. 

`applicaiton.properties`

    quarkus.hibernate-orm.database.generation=drop-and-create  # BE CAREFULL WITH THIS!!!

This will drop (delete) all tables in your DB and recreate the LIQUIDO schema.

# TESTING

When you have a DB schema, then you can fill data into it. `TestDataCreator.java` is a script that
fills the DB with test data.

The first time you run `TestDataCreator` you must drop-and-create the database as described above! All
future tests can then rely on this set of fixed test data.

//MAYBE: implement a maven pre-test-execution

# Security

### TSL (SSL)

Create a self-signed SSL certificate:

    openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -keyout liquido-TLS-key.pem -out liquido-TLS-cert.pem

Or you can also create a keystore that contains both keys:

    keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore liquido-keystore.p12 -validity 3650

then add in `application.properties` and don't forget to adapt all URLs in frontend config files to https://....

    # TLS certificates for encrypted HTTPS connection
    quarkus.http.ssl.certificate.file=/path/cert.pem
    quarkus.http.ssl.certificate.key-file=/path/key.pem
    
    # enabled, redirect or disabled(=only allow HTTPS requests)
    quarkus.http.insecure-requests=disabled

### Oauth

Why I hate Oauth: https://news.ycombinator.com/item?id=35713518

### WebAuthn - Passwordless login

In the end I used the quarkus plugin. After refactoring it for blocking DB access.
https://quarkus.io/guides/security-webauthn

This lib also looks nice. Has a good article.
https://developers.yubico.com/java-webauthn-server/

Nice easy simple sequence diagram for webauthn registration and authentication:
https://passwordless.id/protocols/webauthn/2_registration

https://webauthn.io
https://webauthn.guide

Nice funny written article
https://medium.com/digitalfrontiers/webauthn-with-spring-security-c9175aae3e06




# Native Build

FIXME:
https://stackoverflow.com/questions/56871033/how-to-fix-org-apache-commons-logging-impl-logfactoryimpl-not-found-in-native

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