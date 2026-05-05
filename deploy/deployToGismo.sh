#!/bin/sh

echo
echo "Deploying LIQUIDO to GISMO"
echo
set -e

echo "Copy LIQUIDO quarkus-app to gismo:/opt/liquido-on-gismo"

rsync --recursive --delete-after ./target/quarkus-app/ gismo:/opt/liquido-on-gismo/

rsync --ignore-times ./config/application-gismo.properties gismo:/opt/liquido-on-gismo/config/

echo
printf "Do you want to restart LIQUIDO on GISMO? [y/N] "
read restart_liquido

case "$restart_liquido" in
	[yY]|[yY][eE][sS])
		printf "Restarting LIQUIDO on GISMO: "
		ssh gismo 'sudo /bin/systemctl restart liquido'
		if ssh gismo 'sudo /bin/systemctl is-active --quiet liquido'; then
			echo "✅"
		else
			status_output="$(ssh gismo 'sudo /bin/systemctl status liquido --no-pager' || true)"
			echo "LIQUIDO service is not active (running)"
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
    echo " => Backend is alive. ✅"
    break
  fi
  if [ "$check_attempt" -eq "$max_attempts" ]; then
    echo " => FAILED! (${check_attempt}/${max_attempts}): $response"
    exit 1
  fi
  check_attempt=$((check_attempt + 1))
  sleep 1
done

echo
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