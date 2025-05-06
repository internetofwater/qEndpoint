#!/usr/bin/env bash
QEP_LOCATION=/app/qendpoint
INDEX_SUFFIX="index.v1-1"
INDEX_HDT_DIR="$QEP_LOCATION/hdt-store"
INDEX_HDT="index_dev.hdt"
INDEX_HDT_COINDEX="$INDEX_HDT.$INDEX_SUFFIX"
PREFIXES_FILE="$QEP_LOCATION/prefixes.sparql"
#CDN=https://qanswer-svc4.univ-st-etienne.fr
#CDN=https://frink-lakefs.apps.renci.org/api/v1



# THIS SCRIPT REQUIRES THE FOLLOWING ENVIRONMENT VARIABLES
# TO BE SET BY THE USER
# FRINK_RENCI_REPO_NAME
# FRINK_RENCI_BRANCH_NAME
# RCLONE_CONFIG_LAKEFS_ACCESS_KEY_ID
# RCLONE_CONFIG_LAKEFS_SECRET_ACCESS_KEY


# THESE VARIABLES ARE SET INTERNALLY
export RCLONE_CONFIG_LAKEFS_TYPE=s3
export RCLONE_CONFIG_LAKEFS_PROVIDER=Other
export RCLONE_CONFIG_LAKEFS_ENDPOINT=https://frink-lakefs.apps.renci.org/

export RCLONE_CONFIG_GCS_TYPE="google cloud storage"
export RCLONE_CONFIG_GCS_ENV_AUTH=true

if [ -z $FRINK_RENCI_REPO_NAME ]; then
  echo "FRINK_RENCI_REPO_NAME not set!" && exit 1
fi

if [ -z $FRINK_RENCI_BRANCH_NAME ]; then
  echo "FRINK_RENCI_BRANCH_NAME not set!" && exit 1
fi

if [ -z $RCLONE_CONFIG_LAKEFS_ACCESS_KEY_ID ]; then
   echo "RCLONE_CONFIG_LAKEFS_ACCESS_KEY_ID not set!" && exit 1
fi

if [ -z $RCLONE_CONFIG_LAKEFS_SECRET_ACCESS_KEY ]; then
   echo "RCLONE_CONFIG_LAKEFS_SECRET_ACCESS_KEY is not set!" && exit 1
fi



cp -v iow-prefixes.sparql $PREFIXES_FILE 

#HDT="$CDN/$HDT_BASE.hdt"

# CONSTRUCT FRINK/RENCI PATH NAMING CONVENTION TO THE PRODUCED HDT GRAPH ARTIFACT
HDT="lakefs:$FRINK_RENCI_REPO_NAME/$FRINK_RENCI_BRANCH_NAME/hdt/graph.hdt"
HDTDEST=${DEST:-"gcs:geoconnex-graph"}

echo "HDT path at $HDT"

if [ -n "$INDEX_HDT_DIR" ] && [ -f "$INDEX_HDT_DIR/$INDEX_HDT" ]; then
    echo "$INDEX_HDT exists."
else
    echo "starting..."
    echo "Downloading the HDT index from $HDT to $HDTDEST..."
    mkdir -p qendpoint/hdt-store || exit 


    if [ -f "$INDEX_HDT_DIR/graph.hdt" ]; then
        rm "$INDEX_HDT_DIR/graph.hdt"
    fi

    
    #wget --progress=bar:force:noscroll -c --retry-connrefused --tries 0 --timeout 10 -O $INDEX_HDT.tmp $HDT || exit 1
    rclone copy -vv --progress $HDT $HDTDEST || exit 1

    mv $INDEX_HDT_DIR/graph.hdt $INDEX_HDT_DIR/$INDEX_HDT || exit 1

fi

if [ -n "$INDEX_HDT_DIR" ] && [ -f "$INDEX_HDT_DIR/$INDEX_HDT_COINDEX" ]; then
    echo "$INDEX_HDT_COINDEX exists."
else
    echo "Downloading the HDT co-index $HDT.$INDEX_SUFFIX into $HDTDEST..."

    if [ -f "$INDEX_HDT_COINDEX.tmp" ]; then
        rm "$INDEX_HDT_COINDEX.tmp"
    fi

    #wget --progress=bar:force:noscroll -c --retry-connrefused --tries 0 --timeout 10 -O $INDEX_HDT_COINDEX.tmp "$HDT.index.v1-1"
    rclone copy -vv --progress $HDT.$INDEX_SUFFIX $HDTDEST || exit 1

    mv $INDEX_HDT_DIR/graph.hdt.$INDEX_SUFFIX $INDEX_HDT_DIR/$INDEX_HDT_COINDEX || exit 1

fi
