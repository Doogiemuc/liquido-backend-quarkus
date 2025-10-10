# Use maven for building the quarkus application
FROM maven:3.9.6-eclipse-temurin-17 AS build

# Set the working directory for the build
WORKDIR /build

# Set build arguments
ARG QUARKUS_DATASOURCE_DB_KIND
ARG QUARKUS_DATASOURCE_USERNAME
ARG QUARKUS_DATASOURCE_PASSWORD
ARG QUARKUS_DATASOURCE_JDBC_URL


# Debug: Print the received build arguments
RUN echo "\n\n\n========== BUILD TIME VARS ============\n" && \
    echo "DB_KIND:     ${QUARKUS_DATASOURCE_DB_KIND}\n" && \
    echo "DB_USERNAME: ${QUARKUS_DATASOURCE_USERNAME}\n" && \
    echo "DB_PASSWORD: ${QUARKUS_DATASOURCE_PASSWORD}\n" && \
    echo "DB_JDBC_URL: ${QUARKUS_DATASOURCE_JDBC_URL}\n\n\n"


# make values availalbe in environment at buildtime
ENV QUARKUS_DATASOURCE_DB_KIND=$QUARKUS_DATASOURCE_DB_KIND
ENV QUARKUS_DATASOURCE_USERNAME=$QUARKUS_DATASOURCE_USERNAME
ENV QUARKUS_DATASOURCE_PASSWORD=$QUARKUS_DATASOURCE_PASSWORD
ENV QUARKUS_DATASOURCE_JDBC_URL=$QUARKUS_DATASOURCE_JDBC_URL

# Copy the Maven project files and build dependencies first (for better caching)
COPY pom.xml .

# Download dependencies and cache the docker layer
# RUN mvn dependency:go-offline

# Copy config and sources
COPY config ./config
COPY src ./src

# Build the application, skipping tests for faster builds, -B = batch mode, no progress bars
RUN mvn -B package -DskipTests

#
# Use a lightweight runtime image for the runtime container
#
FROM eclipse-temurin:23.0.2_7-jre-alpine AS runtime

# Set the working directory inside the container
WORKDIR /app

# Set environment variables for runtime
ENV QUARKUS_DATASOURCE_DB_KIND=$QUARKUS_DATASOURCE_DB_KIND
ENV QUARKUS_DATASOURCE_USERNAME=$QUARKUS_DATASOURCE_USERNAME
ENV QUARKUS_DATASOURCE_PASSWORD=$QUARKUS_DATASOURCE_PASSWORD
ENV QUARKUS_DATASOURCE_JDBC_URL=$QUARKUS_DATASOURCE_JDBC_URL

# Debug: Print the environment variables
RUN set

#
# Copy the built application files from the build stage
#COPY --from=build /build/target/quarkus-app/lib/ /app/lib/
#COPY --from=build /build/target/quarkus-app/*.jar /app/
#COPY --from=build /build/target/quarkus-app/app/ /app/app/
#COPY --from=build /build/target/quarkus-app/quarkus/ /app/quarkus/
COPY --from=build /build/target/quarkus-app /app/
COPY --from=build /build/src/main/resources /resources

# Expose the application port
EXPOSE 8080

# Set the user to run the application (optional for security)
USER 1001

# Define environment variables for Java and Quarkus
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"
#TODO: ENV QUARKUS_PROFILE=dev

# Start the application
CMD ["java", "-jar", "/app/quarkus-run.jar"]