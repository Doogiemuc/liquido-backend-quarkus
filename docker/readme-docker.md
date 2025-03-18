# Liquido inside Docker 

This file contains configuration files to build a docker container for LIQUIDO


### Nice usefull docker one liners

Start the container, but do not start the app yet, but shell into it.
Very nice for debugging pathes inside a container.
`docker compose run --service-ports --entrypoint sh app`