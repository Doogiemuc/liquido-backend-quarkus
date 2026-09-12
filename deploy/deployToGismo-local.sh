#!/bin/sh
# SUPERSEDED as of 2026-09-11: GISMO now runs LIQUIDO via Docker (see
# docker/docker-compose.yml and deployToGismo-docker.sh), not the native systemd
# service this script drives. The systemd unit has been stopped. Kept only for
# reference/backup - do not use for a live deploy.
#
# Local variant of deployToGismo.sh, for running directly on GISMO itself
# (Claude Code sessions in this project run on GISMO, not on the dev laptop,
# so the rsync/ssh-to-gismo in the original script doesn't apply here.)
#
# Note: config/application-gismo.properties is gitignored and not in the repo.
# The backed-up copy lives at ~/Coding/liquido/config/application-gismo.properties
# and is (re-)copied into place on every run below, because the quarkus-app
# rsync below uses --delete-after against /opt/liquido-on-gismo/, which wipes
# out anything not in ./target/quarkus-app/ - including config/ - every time.

echo
echo "Deploying LIQUIDO locally on GISMO"
echo
set -e

printf "Do you want to build the backend [Y/n/t] "
read build_backend
case "$build_backend" in
	[tT])
		printf "Building LIQUIDO backend WITH tests... "
		if ./mvnw clean package; then
      echo "✅ Backend build and tested successfully.\n"
    else
      echo "❌ Backend build failed.\n"
      exit 1
    fi
		;;
  [nN])
    echo "Won't build."
    ;;
	*)
		printf "Building LIQUIDO backend WITHOUT tests ... "
		if ./mvnw clean package -Dmaven.test.skip; then
      echo "✅ Backend build successfully. (WITHOUT running any tests.)\n"
    else
      echo "❌ Backend build failed.\n"
      exit 1
    fi
		;;

esac

echo
echo "Copy LIQUIDO quarkus-app to /opt/liquido-on-gismo (local copy)"
rsync --recursive --delete-after ./target/quarkus-app/ /opt/liquido-on-gismo/

echo
echo "Restoring config from backup (~/Coding/liquido/config/application-gismo.properties)"
CONFIG_BACKUP="$HOME/Coding/liquido/config/application-gismo.properties"
if [ ! -f "$CONFIG_BACKUP" ]; then
  echo "❌ Config backup not found at $CONFIG_BACKUP - aborting before restart."
  exit 1
fi
mkdir -p /opt/liquido-on-gismo/config
rsync --progress --ignore-times "$CONFIG_BACKUP" /opt/liquido-on-gismo/config/

echo
printf "Do you want to restart LIQUIDO on GISMO? [Y/n] "
read restart_liquido

case "$restart_liquido" in
  [nN])
      echo "Won't restart."
      ;;
	*)
		printf "Restarting LIQUIDO: "
		sudo /bin/systemctl restart liquido
		if sudo /bin/systemctl is-active --quiet liquido; then
			echo " => Backend restarted successfully ✅"
		else
			status_output="$(sudo /bin/systemctl status liquido --no-pager || true)"
			echo "❌ LIQUIDO service is not active (running)"
			echo "$status_output"
			exit 1
		fi
		;;
esac

LIQUIDO_API_URL="https://liquido.dynv6.net/api/v2/"
LIQUIDO_FRONTEND_URL="https://liquido.dynv6.net/"

echo
check_attempt=1
max_attempts=10
while [ "$check_attempt" -le "$max_attempts" ]; do
  if [ "$check_attempt" -eq 1 ]; then
    printf "Checking backend  at $LIQUIDO_API_URL (%s/%s)" "$check_attempt" "$max_attempts"
  else
    printf "\rChecking backend  at $LIQUIDO_API_URL (%s/%s)" "$check_attempt" "$max_attempts"
  fi
  response="$(curl -k --silent --show-error $LIQUIDO_API_URL || true)"
  if printf '%s' "$response" | grep -q 'LIQUIDO'; then
    echo " => ✅ Backend is alive."
    break
  fi
  if [ "$check_attempt" -eq "$max_attempts" ]; then
    echo " => ❌ FAILED! (${check_attempt}/${max_attempts}): $response"
    exit 1
  fi
  check_attempt=$((check_attempt + 1))
  sleep 1
done

printf "Checking frontend at $LIQUIDO_FRONTEND_URL"
response="$(curl -k --silent --show-error $LIQUIDO_FRONTEND_URL || true)"
if printf '%s' "$response" | grep -qi '^<html' \
  && printf '%s' "$response" | grep -q 'LIQUIDO is loading'; then
  echo " => Frontend is OK ✅"
else
  echo " => FAILED! Frontend did not return valid HTML."
  echo "$response"
  exit 1
fi

echo
echo "Deployed successfully ✅ => Now go check on a real mobile device! :-)"
