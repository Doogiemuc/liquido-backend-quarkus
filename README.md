# liquido-backend-quarkus

This is the java backend for http://www.liquido.vote developed with [Quarkus](https://quarkus.io).

LIQUIDO is a free, secure and open eVoting application. 

# Run LIQUIDO locally for development

Before you can run the LIQUIDO backend service you must make sure, that some preconditions are fullfielled. 

## LIQUIDO configuration

Check all settings in `config/application.properties` add your own `config/application-local.properties`.

## PostgreSQL database

The LIQUIDO backend needs an SQL database. You can either spin up one and configure that as a datasource. Or for testing you can use 
the in-memory H2 database that Quarkus automatically starts for you in dev mode.

    -Dquarkus.profile=dev

## Create a self-signed TLS certificate for HTTPS

The LIQUIDO backend API is only reachable via HTTPS. You must use a TLS certificate. You can use a self-signed certificate for local development:

    openssl req -x509 -newkey rsa:4096 -days 365 -keyout src/main/resources/liquido-TLS-key.pem -out src/main/resources/liquido-TLS-cert.pem
    (in Windows git-bash prefix with "winpty" !)

Then configure password for this PEM in application.properties 

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```
You can then execute your native executable with: `./target/liquido-backend-quarkus-*-runner`



Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```


If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Related Guides

- JDBC Driver - H2 ([guide](https://quarkus.io/guides/datasource)): Connect to the H2 database via JDBC
- YAML Configuration ([guide](https://quarkus.io/guides/config#yaml)): Use YAML to configure your Quarkus application
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplify your persistence code for Hibernate ORM via the active record or the repository pattern
- SmallRye GraphQL ([guide](https://quarkus.io/guides/microprofile-graphql)): Create GraphQL Endpoints using the code-first approach from MicroProfile GraphQL

## Provided Code

### YAML Config

Configure your application with YAML

[Related guide section...](https://quarkus.io/guides/config-reference#configuration-examples)

The Quarkus application configuration is located in `src/main/resources/application.yml`.

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)

[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)


### SmallRye GraphQL

Start coding with this Hello GraphQL Query

[Related guide section...](https://quarkus.io/guides/smallrye-graphql)