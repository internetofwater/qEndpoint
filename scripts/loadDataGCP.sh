#!/usr/bin/bash
QEP_LOCATION=/app/qendpoint
INDEX_SUFFIX="index.v1-1"
INDEX_HDT_DIR="$QEP_LOCATION/hdt-store/"
INDEX_HDT="index_dev.hdt"
INDEX_HDT_COINDEX="$INDEX_HDT.$INDEX_SUFFIX"
PREFIXES_FILE="$QEP_LOCATION/prefixes.sparql"

export RCLONE_CONFIG_GCS_TYPE="google cloud storage"
export RCLONE_CONFIG_GCS_ENV_AUTH=true

HDTSRC=${HDT:-"gcs:geoconnex-graph"}

rclone copy -vv --fast-list --progress --buffer-size=128M --use-mmap $HDTSRC $INDEX_HDT_DIR || exit 1