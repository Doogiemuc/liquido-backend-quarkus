# Use Maven for building the application
FROM maven:3.9.6-eclipse-temurin-17 AS build

# Set the working directory for the build
WORKDIR /build

# Set build arguments
#ARG DB_USER
#ARG DB_PASSWORD
#ARG DB_NAME
#ARG DB_PORT

# Debug: Print the received build arguments
#RUN echo "========== BUILD TIME VARS ============"
#RUN echo "DB_USER: $DB_USER"
#RUN echo "DB_PASSWORD: $DB_PASSWORD"
#RUN echo "DB_NAME: $DB_NAME"

# make values availalbe in environment at buildtime
#ENV DB_USER=${DB_USER}
#ENV DB_PASSWORD=${DB_PASSWORD}
#ENV DB_NAME=${DB_NAME}
#ENV DB_PORT=${DB_PORT}

# Debug: Print the environment variables
#RUN set

# Copy the Maven project files and build dependencies first (for better caching)
COPY pom.xml .
COPY config ./config

# Download dependencies and cache the docker layer
RUN mvn dependency:go-offline

# Copy the sources
COPY src ./src

# Build the application, skipping tests for faster builds, -B = batch mode, no progress bars
RUN mvn -B package -DskipTests



# Use a lightweight runtime image for the final build
FROM eclipse-temurin:17-jre AS runtime

# Set the working directory inside the container
WORKDIR /app

# Set environment variables for runtime
#ENV DB_USER=${DB_USER}
#ENV DB_PASSWORD=${DB_PASSWORD}
#ENV DB_NAME=${DB_NAME}
#ENV DB_PORT=${DB_PORT}
#
## Debug: Print the environment variables
#RUN set
#
# Copy the built application files from the build stage
COPY --from=build /build/target/quarkus-app/lib/ /app/lib/
COPY --from=build /build/target/quarkus-app/*.jar /app/
COPY --from=build /build/target/quarkus-app/app/ /app/app/
COPY --from=build /build/target/quarkus-app/quarkus/ /app/quarkus/

# Expose the application port
EXPOSE 8080

# Set the user to run the application (optional for security)
USER 1001

# Define environment variables for Java and Quarkus
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

# Start the application
CMD ["java", "-jar", "/app/quarkus-run.jar"]