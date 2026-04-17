#!/bin/sh

echo
echo "Deploying LIQUIDO to GISMO"
echo
set -e

rsync --recursive --delete-after -v ./target/quarkus-app/ gismo:/opt/liquido-on-gismo/

rsync --ignore-times -v ./config/application-gismo.properties gismo:/opt/liquido-on-gismo/config/

echo "Deployed successfully ✅"