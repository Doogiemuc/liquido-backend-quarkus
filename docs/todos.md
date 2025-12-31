# These are the next todos I am planning for LIQUIDO


 * Change registration flow to WebAuthN credentialId only. "There are no passwords in LIQUIDO!"
   Prompt: "I have a PWA frontend that talks to a Java Quarkus backend. I want to implemenet a highly secure login via webauthn in my applicaiton. What use case flow would you suggest.   Should my users first register only with username and password and then later add webauthn authenticator as a second factor?   Or another use case flow?"
 * Warn when user registers a second authenticator. Add lables and lastUsed to webauthncredential
 * io.quarkus.vault.VaultTOTPSecretEngine    https://www.the-main-thread.com/p/secure-java-api-totp-quarkus-vault
 * Update to Quarkus v3.30.x - or can I stay with the v27.0.0 LTS = long-term-support(!) https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.30
 * Native Build of docker container (started)
 * Checkout all the //TODO annotations in the code
 * Checkout Quarkus SimpleScheduler for cleanup operations in the background.
 * Prevent loops in delegation chain!