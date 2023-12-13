# LIQUIDO - Technical Reference

# CHANGELOG (only most important new features)

 * 2023-06-23 GREEN Test with complete Happy Case
 * 2023-07-04 Upgraded to Quarkus 3.1.3.Final
 * 2023-07-04 Working on native build
 * 2023-07-24 CreatedBy is now automatically added in Entities
 * 2023-11-01 Added TLS for backend HTTPS
 * 2023-11-22 Even more work on UI layout. It's starting to look smooth.

# Next TODOs

 * Nativ Build
 * Checkout all the //TODO annotations in the code 
 * Checkout Quarkus SimpleScheduler for cleanup operations in the background.

# TESTING

The first time you run `TestDataCreator` you must drop-and-create the database! All
future tests will rely on this data.

MAYBE: implement a maven pre-test-execution

# DATABASE

### ORM Database mapping

We use Quarkus-Panache
https://quarkus.io/guides/hibernate-orm-panache


# Security

### TSL (SSL)

Create a self-signed SSL certificate:

     openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -keyout key.pem -out cert.pem

then add in `application.properties` and don't forget to adapt all URLs in config files to https://....

    # TLS certificates for encrypted HTTPS connection
    quarkus.http.ssl.certificate.file=/path/cert.pem
    quarkus.http.ssl.certificate.key-file=/path/key.pem
    
    # enabled, redirect or disabled(=only allow HTTPS requests)
    quarkus.http.insecure-requests=disabled


# Nativ Build

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

# WebAuthn - Passwordless login

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