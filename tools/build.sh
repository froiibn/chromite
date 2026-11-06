#!/usr/bin/env sh

set -e

###############################################################################
# Function Definitions
###############################################################################

# Get the directory where the script is located
# Returns: Absolute path to script directory
SCRIPT_DIR="$(realpath "$(dirname "$0")")"

# Function: log
# Purpose: Provides consistent logging format with timestamps
# Arguments:
#   $1 - Message to log
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1"
}

###############################################################################
# Path Definitions and Validation
###############################################################################

# Define paths relative to the script location.
# dbeaver-common and datadam-api are part of this repository (deps/), see docs/chromite/build.md
DBEAVER_COMMON_DIR="${SCRIPT_DIR}/../deps/dbeaver-common"
PRODUCT_DIR="${SCRIPT_DIR}/../product"
AGGREGATE_DIR="${PRODUCT_DIR}/aggregate"

# Simple check for product directory
if [ ! -d "$PRODUCT_DIR" ]; then
    log "Error: Product directory not found at $PRODUCT_DIR"
    exit 1
fi

###############################################################################
# Build Process
###############################################################################

# Execute Maven build
log "Starting Maven build..."

"$DBEAVER_COMMON_DIR/mvnw" clean install -Pproduct-chromite-swing -T 1C -f "$AGGREGATE_DIR"

log "Build completed successfully"