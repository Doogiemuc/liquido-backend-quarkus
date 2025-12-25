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
 * 2025-12-21 Working on passwordless login via webauthn

# Next TODOs

 See todos.md

# DATABASE

We use Quarkus-Panache
https://quarkus.io/guides/hibernate-orm-panache

### Initialize the database

On Startup liquido checks for the most important tables. You **have to** create a DB schema first. 

`application.properties`

    quarkus.hibernate-orm.database.generation=drop-and-create  # BE CAREFULL WITH THIS!!! DO THIS ONLY ONCE!!!

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
    
    brew install mkcert
    mkcert -install            # run once
    #TODO:  How to set better common name with mkcert? 
    #TODO:  On Mac: Add this cert to your local keystore and mark it as "always trust"
    mkcert liquido.local localhost 127.0.0.1 192.168.178.134  
      or by hand
    openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -keyout liquido-vote-key.pem -out liquido-vote-cert.pem

Or you can also create a keystore that contains both keys:

    keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore liquido-keystore.p12 -validity 3650

Check your certs with

    openssl x509 -in ./liquido-vote-cert.pem -text -noout

in frontend `vite.config.js`

    const key = fs.readFileSync(path.resolve(__dirname, 'tls-certs/liquido-local-key.pem'), 'utf8');
    const cert = fs.readFileSync(path.resolve(__dirname, 'tls-certs/liquido-local-cert.pem'), 'utf8');
    export default defineConfig({
      server: {
		https: {													// serve frontend over HTTPS. => but not on fly.io
          key: key,
          cert: cert
		},			    
		host: true, // "0.0.0.0",  				// "0.0.0.0" = listen on all adresses, incl. LAN and public adresses
		port: 3001,
		strictPort: true,    							// only use this port. Exit if not available
		allowedHosts: ["localhost", "127.0.0.1"],
     ...

then add in `application.properties` and don't forget to adapt all URLs in frontend config files to https://....

    # TLS certificates for encrypted HTTPS connection. Must use path for "mvn quarkus:dev" !
    quarkus.http.ssl.certificate.files=src/main/resources/liquido-local-cert.pem
    quarkus.http.ssl.certificate.key-files=src/main/resources/liquido-local-key.pem
    
    # enabled, redirect or disabled(=only allow HTTPS requests)
    quarkus.http.insecure-requests=disabled

Remark: There would also be a nice little server that automatically creates HTTPs/TLS certs:  https://caddyserver.com/


### Oauth

Why I hate Oauth: https://news.ycombinator.com/item?id=35713518

### WebAuthn - Passwordless login

WebAuthn is a standard with a browser side (navigator.credentials) and a server side (validation, challenge generation, credential storage). The workflow looks like this:

Registration (attestation)
1.	Client requests a registration challenge from the server.
2.	Server generates a challenge and sends it along with user info to the client.
3.	Client calls navigator.credentials.create() with the challenge. User/human being provides biometric data.
4.	Client sends the result back to the server.
5.	Server validates the attestation, stores the public key & credential ID.

Login (assertion)
1.	Client requests a login challenge from the server.
2.	Server generates a challenge and sends it to the client.
3.	Client calls navigator.credentials.get() with the challenge.
4.	Client sends the assertion back to the server.
5.	Server verifies the signature and logs the user in.

I am using the quarkus-security-webauthn library. It uses webauthn4j under the hood.
https://quarkus.io/guides/security-webauthn

This lib also looks nice, has better, more understandable documentation
https://developers.yubico.com/java-webauthn-server/

Nice easy simple sequence diagram for webauthn registration and authentication:
https://passwordless.id/protocols/webauthn/2_registration

Nice webpage with demo login
https://webauthn.io
Beautiful detailed technical explanations
https://webauthn.guide
Passkeys are a replacement for passwords. A password is something that can be remembered and typed, and a passkey is a secret stored on one’s devices, unlocked with biometrics.
https://passkeys.dev/

Nice funny written article
https://medium.com/digitalfrontiers/webauthn-with-spring-security-c9175aae3e06




# Docker Native Build

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