# Liquido inside Docker 

This file contains configuration files to build a docker container for LIQUIDO

### Nice useful docker one liners

Start the container, but do not start the app yet, but shell into it.
Very nice for debugging pathes inside a container.
`docker compose run --service-ports --entrypoint sh app`

Execute a command inside a container
`docker exec -it liquido-backend sh`


# Deployment to my local server via docker

Build the docker image. My Dockerfiles are in a subdirectory. And mind the dot at the and! 

    docker build -t liquido-backend:latest -f docker/Dockerfile .

Build image for AMD64 (linux) architecture

    docker buildx build --platform linux/amd64 -t liquido-backend-amd64 -f docker/Dockerfile .

Copy a docker image to server and start it

    docker save -o liquido-backend.tar liquido-backend-amd64:latest
    docker load liquido-backend-amd64.tar

    # or in one line
    docker save liquido-backend | ssh -C user@my.remote.host.com docker load`

Start thecontainer: 

    sudo docker run --rm --env-file .env -p 8080:8080 --name liquido-backend-amd64 liquido-backend-amd64

    -rm  remove container after it stops
    -d   detach: run container to background

# Liquido backend in public internet

External hostname, dynamic IP

    liquido.dynv6.net



# EASY: 100% manual deployment without docker (Yes I know ... :-(

````
./mvnw package -DskipTests        # Build JAR
scp -r target/quarkus-app/ doogie@gismo:~/Coding/liquido/int   # Copy JAR _and_ libs
java -jar quarkus-run.jar
````