#!/usr/bin/env bash
# Assembles the Hugging Face Space repository for the single-container demo (branch `demo`) from the committed tree
# (git archive HEAD): only what the image build needs, never untracked files such as .env or docs/audit.
#   deploy/huggingface/build-space.sh [OUT_DIR]      default: build/hf-space
#   hf upload <owner>/<space> build/hf-space . --repo-type space
set -euo pipefail
cd "$(dirname "$0")/../.."

out=${1:-build/hf-space}
# Untracked files are never archived, so only changes to tracked files block the build.
if [ -n "$(git status --porcelain --untracked-files=no -- apps database infrastructure/docker deploy/huggingface)" ]; then
  echo "Commit the changes under apps/, database/, infrastructure/docker/ and deploy/huggingface/ first:" >&2
  echo "the Space is built from HEAD only." >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"
git archive HEAD -- \
  apps/backend/gradlew apps/backend/settings.gradle.kts apps/backend/build.gradle.kts apps/backend/gradle.properties \
  apps/backend/gradle apps/backend/src \
  apps/frontend \
  database/migrations \
  infrastructure/docker/keycloak/diligence-realm.json infrastructure/docker/keycloak/password-blacklists \
  infrastructure/docker/scripts/keycloak-bootstrap.sh infrastructure/docker/scripts/demo-setup.sh \
  infrastructure/docker/scripts/demo-seed.py \
  deploy/huggingface \
  | tar -x -C "$out"

# The Space expects its Dockerfile and README (with the Space settings) at the repository root.
cp deploy/huggingface/Dockerfile "$out/Dockerfile"
cp deploy/huggingface/SPACE_README.md "$out/README.md"
cat > "$out/.dockerignore" << 'EOF'
**/node_modules
**/.next
**/build
**/.gradle
**/.env*
EOF
rm -rf "$out/apps/frontend/tests" "$out/apps/frontend/e2e"
echo "Space repository ready in $out ($(du -sh "$out" | cut -f1)), commit $(git rev-parse --short HEAD)."
