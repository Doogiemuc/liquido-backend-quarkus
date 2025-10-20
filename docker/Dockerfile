#
# Stage 1: Build my quarkus application with maven
#
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /build

# Set build arguments
#ARG QUARKUS_DATASOURCE_DB_KIND
#ARG QUARKUS_DATASOURCE_USERNAME
#ARG QUARKUS_DATASOURCE_PASSWORD
#ARG QUARKUS_DATASOURCE_JDBC_URL

# Debug: Print the received build arguments
#RUN echo "BUILD TIME VARS:" && \
#    # echo "DB_KIND: ${QUARKUS_DATASOURCE_DB_KIND}" && \
#    echo "DB_USERNAME: ${QUARKUS_DATASOURCE_USERNAME}" && \
#    echo "DB_JDBC_URL: ${QUARKUS_DATASOURCE_JDBC_URL}"

# make values availalbe in environment at buildtime
#ENV QUARKUS_DATASOURCE_DB_KIND=$QUARKUS_DATASOURCE_DB_KIND
#ENV QUARKUS_DATASOURCE_USERNAME=$QUARKUS_DATASOURCE_USERNAME
#ENV QUARKUS_DATASOURCE_PASSWORD=$QUARKUS_DATASOURCE_PASSWORD
#ENV QUARKUS_DATASOURCE_JDBC_URL=$QUARKUS_DATASOURCE_JDBC_URL

# Copy the Maven project files
COPY pom.xml .

# Pre-download dependencies to use cache effectively
RUN mvn verify -DskipTests

# Copy sources and my quarkus configurations
COPY config ./config/
RUN echo "--- FILES IN /config AFTER copy ---" && ls -l ./config/*
COPY src ./src

# Build the application, skipping tests for faster builds, -B = batch mode, no progress bars
RUN mvn -B package -DskipTests


#
# Stage 2: Use a lightweight runtime image for the runtime container
# Eclipse temurin is build on tip of alpine + Jave Runtime (JRE)
#
FROM eclipse-temurin:23-jre-alpine AS runtime

# Set the working directory inside the container
WORKDIR /app

# Set environment variables for runtime
#ENV QUARKUS_DATASOURCE_DB_KIND=$QUARKUS_DATASOURCE_DB_KIND
#ENV QUARKUS_DATASOURCE_USERNAME=$QUARKUS_DATASOURCE_USERNAME
#ENV QUARKUS_DATASOURCE_PASSWORD=$QUARKUS_DATASOURCE_PASSWORD
#ENV QUARKUS_DATASOURCE_JDBC_URL=$QUARKUS_DATASOURCE_JDBC_URL

# Debug: Print the environment variables
# RUN set

# Copy the built application files from the build stage
COPY --from=build /build/config /app/config
COPY --from=build /build/src/main/resources /app/resources
COPY --from=build /build/target/quarkus-app /app/

# Expose the application port
EXPOSE 8080

# Set the user to run the application (optional for security)
USER 1001

# Define environment variables for Java and Quarkus
# ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"
#Can also set quarkus profile here: ENV QUARKUS_PROFILE=dev

# --- DEBUG COMMAND HERE ---
RUN echo "--- FILES IN /app/config AFdfgdfgTER COPY ---" && ls -l /app/config
# --- END DEBUG COMMAND ---

# Start the application
CMD ["java", "-jar", "/app/quarkus-run.jar", "-Dquarkus.profile=fly-int"]
# CMD ["tail", "-f", "/dev/null"]   # Just start the container and do not start the app yet. Nice for debugging with   docker exec -it liquido-backend sh