#!/bin/bash

OPENHAB_ADDON_DIR="/usr/share/openhab2/addons"
TARGET_HOSTNAME="openhabiandevice"

set -e

mvn spotless:apply clean package $1 -nsu

cd target
BINDING=`ls org.openhab.binding.forecast*SNAPSHOT.jar`

echo "Binding: $BINDING"

scp $BINDING openhabian@$TARGET_HOSTNAME:/home/openhabian
ssh openhabian@$TARGET_HOSTNAME "mv $BINDING $OPENHAB_ADDON_DIR"

echo "Successfully copied to raspi"
