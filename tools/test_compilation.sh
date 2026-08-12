#!/bin/bash

# This script tests the compilation of the daemon patches by:
# 1. Creating a temporary workspace.
# 2. Cloning the current repository (which is based on scrcpy).
# 3. Resetting the workspace to the 'master' branch (pristine scrcpy).
# 4. Applying the patches from the current 'patches_queue' directory.
# 5. Running the Gradle build for the server.

# Get the absolute path of the current repository
REPO_ROOT=$(git rev-parse --show-toplevel)
PATCHES_DIR="${REPO_ROOT}/patches_queue/server"

echo "Repository Root: ${REPO_ROOT}"
echo "Patches Directory: ${PATCHES_DIR}"

if [ ! -d "${PATCHES_DIR}" ]; then
    echo "Error: Patches directory not found: ${PATCHES_DIR}"
    exit 1
fi

# Create a temporary workspace
WORKSPACE=$(mktemp -d)
echo "Created temporary workspace: ${WORKSPACE}"

# Clone the current repo to the workspace
echo "Cloning repository to workspace..."
git clone "${REPO_ROOT}" "${WORKSPACE}" --depth 1 --no-single-branch
cd "${WORKSPACE}"

# Reset to master (assumed to be pristine scrcpy)
echo "Resetting workspace to master branch..."
git checkout master

# Apply all patches from the source patches_queue
echo "Applying patches..."
find "${PATCHES_DIR}" -name "*.patch" | sort | while read patch_path; do
    echo "Applying $(basename "${patch_path}")..."

    # We use git apply.
    # The patches are relative to the project root in the patch file usually,
    # but based on the provided file paths, they seem to be prefixed with 'server/'.
    # git apply --check will verify if it can be applied.
    if ! git apply "${patch_path}"; then
        echo "Error: Failed to apply patch ${patch_path}"
        # Some patches might create new files, ensure they are handled.
        # If git apply fails, we try 'patch' as a fallback for simple diffs.
        patch -p1 < "${patch_path}" || { echo "Fatal: Patch failed completely."; exit 1; }
    fi
done

echo "Starting Gradle build for scrcpy server..."
# Ensure gradlew is executable
chmod +x ./gradlew

# Build the server component
./gradlew :server:assembleDebug

BUILD_RESULT=$?

if [ ${BUILD_RESULT} -eq 0 ]; then
    echo "------------------------------------------------"
    echo "SUCCESS: The patches compile correctly on master."
    echo "------------------------------------------------"
else
    echo "------------------------------------------------"
    echo "FAILURE: Compilation failed."
    echo "------------------------------------------------"
fi

# Suggest cleanup
echo "Workspace preserved at: ${WORKSPACE}"
echo "To clean up, run: rm -rf ${WORKSPACE}"

exit ${BUILD_RESULT}
