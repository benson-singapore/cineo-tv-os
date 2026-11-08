#!/usr/bin/env bash
# Read-only Git evidence collector for release notes.
#
# It never creates, modifies, stages, commits, or tags anything. It only prints
# machine-readable KEY=value fields plus [COMMITS] and [STAT] sections.
#
# Usage:
#   git-release-context.sh --tag v1.2.3 [--repo-root <path>]
#   git-release-context.sh --target-tag v1.2.3 [--from-tag v1.2.2] [--repo-root <path>]
#
# Existing tag mode:
#   --tag v1.2.3          Collect evidence for a tag that already exists.
#
# Planned release mode:
#   --target-tag v1.2.3   The tag that will be created.
#   --from-tag v1.2.2     Optional. Defaults to the newest tag reachable from HEAD.
#
# Exit codes:
#   0  success
#   1  repository/tag/range error
#   2  invalid arguments

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  git-release-context.sh --tag v1.2.3 [--repo-root <path>]
  git-release-context.sh --target-tag v1.2.3 [--from-tag v1.2.2] [--repo-root <path>]

Options:
  --tag <tag>          Inspect an existing tag.
  --target-tag <tag>   The tag that will be created (planned release).
  --from-tag <tag>     Base tag for a planned release. Defaults to newest tag on HEAD.
  --repo-root <path>   Run inside this repository instead of the current directory.
  -h, --help           Show this help.

Output fields:
  TAG, TAG_EXISTS, TARGET_REF, TAG_COMMIT, TAG_DATE, TAG_SUBJECT,
  PREVIOUS_TAG, RANGE, INITIAL_RELEASE,
  HEAD_COMMIT, HEAD_DATE, HEAD_SUBJECT, BRANCH, DIRTY
  [COMMITS]   one line per commit: "<short-hash> <ISO-date> <subject>"
  [STAT]      git diff --stat output for the release range
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

need_value() {
  local option="$1"
  if [[ $# -lt 2 || -z "${2:-}" ]]; then
    echo "error: $option requires a value" >&2
    usage >&2
    exit 2
  fi
}

TAG=""
TARGET_TAG=""
FROM_TAG=""
REPO_ROOT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      need_value "$1" "${2:-}"
      TAG="$2"
      shift 2
      ;;
    --tag=*)
      TAG="${1#*=}"
      shift
      ;;
    --target-tag)
      need_value "$1" "${2:-}"
      TARGET_TAG="$2"
      shift 2
      ;;
    --target-tag=*)
      TARGET_TAG="${1#*=}"
      shift
      ;;
    --from-tag)
      need_value "$1" "${2:-}"
      FROM_TAG="$2"
      shift 2
      ;;
    --from-tag=*)
      FROM_TAG="${1#*=}"
      shift
      ;;
    --repo-root)
      need_value "$1" "${2:-}"
      REPO_ROOT="$2"
      shift 2
      ;;
    --repo-root=*)
      REPO_ROOT="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n "$REPO_ROOT" ]]; then
  cd "$REPO_ROOT" || die "cannot enter repository root: $REPO_ROOT"
fi

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || die "not inside a Git working tree"

if [[ -n "$TAG" && -n "$TARGET_TAG" ]]; then
  die "use either --tag or --target-tag, not both"
fi

if [[ -z "$TAG" && -z "$TARGET_TAG" ]]; then
  echo "error: pass --tag or --target-tag" >&2
  usage >&2
  exit 2
fi

tag_exists() {
  git rev-parse -q --verify "refs/tags/$1" >/dev/null 2>&1
}

newest_tag_reachable_from() {
  local ref="$1"
  git tag --merged "$ref" --sort=-v:refname 2>/dev/null | head -n 1 || true
}

if [[ -n "$TARGET_TAG" ]] && tag_exists "$TARGET_TAG"; then
  echo "warning: target tag $TARGET_TAG already exists; treating HEAD as the planned target. Use --tag $TARGET_TAG to inspect the existing tag." >&2
fi

EMPTY_TREE="$(git hash-object -t tree /dev/null)"

if [[ -n "$TAG" ]]; then
  tag_exists "$TAG" || die "tag not found: $TAG"

  TARGET_TAG="$TAG"
  TARGET_REF="refs/tags/$TAG"
  TAG_EXISTS="true"
  TAG_COMMIT="$(git rev-list -n 1 "refs/tags/$TAG")"

  PREVIOUS_PARENT="$(git rev-parse "refs/tags/$TAG^{commit}^" 2>/dev/null || true)"
  if [[ -n "$PREVIOUS_PARENT" ]]; then
    PREVIOUS_TAG="$(newest_tag_reachable_from "$PREVIOUS_PARENT")"
  else
    PREVIOUS_TAG=""
  fi
else
  TARGET_REF="HEAD"
  TAG_EXISTS="false"
  TAG_COMMIT="$(git rev-parse HEAD)"

  if [[ -n "$FROM_TAG" ]]; then
    tag_exists "$FROM_TAG" || die "from tag not found: $FROM_TAG"
    if ! git merge-base --is-ancestor "refs/tags/$FROM_TAG^{commit}" HEAD; then
      die "from tag $FROM_TAG is not an ancestor of HEAD"
    fi
    PREVIOUS_TAG="$FROM_TAG"
  else
    PREVIOUS_TAG="$(newest_tag_reachable_from HEAD)"
    # Rebuild scenario: the target tag still points at the old release commit.
    # Exclude it so the range starts at the release before it.
    if [[ "$PREVIOUS_TAG" == "$TARGET_TAG" ]]; then
      PREVIOUS_TAG="$(git tag --merged HEAD --sort=-v:refname 2>/dev/null \
        | grep -vxF "$TARGET_TAG" | head -n 1 || true)"
    fi
  fi
fi

TAG_DATE="$(git log -1 --format=%aI "$TARGET_REF")"
TAG_SUBJECT="$(git log -1 --format=%s "$TARGET_REF")"
HEAD_COMMIT="$(git rev-parse HEAD)"
HEAD_DATE="$(git log -1 --format=%aI HEAD)"
HEAD_SUBJECT="$(git log -1 --format=%s HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || printf 'HEAD')"
if [[ -n "$(git status --porcelain 2>/dev/null || true)" ]]; then
  DIRTY="true"
else
  DIRTY="false"
fi

if [[ -z "$PREVIOUS_TAG" ]]; then
  INITIAL_RELEASE="true"
  RANGE="$TARGET_TAG"
  COMMITS="$(git log --format='%h %aI %s' "$TARGET_REF")"
  STAT="$(git diff --stat "$EMPTY_TREE" "$TARGET_REF")"
else
  INITIAL_RELEASE="false"
  if [[ "$TAG_EXISTS" == "true" ]]; then
    RANGE="$PREVIOUS_TAG..$TARGET_TAG"
  else
    RANGE="$PREVIOUS_TAG..HEAD"
  fi
  COMMITS="$(git log --format='%h %aI %s' "$PREVIOUS_TAG..$TARGET_REF")"
  STAT="$(git diff --stat "$PREVIOUS_TAG" "$TARGET_REF")"
fi

[[ -n "$COMMITS" ]] || COMMITS="(none)"
[[ -n "$STAT" ]] || STAT="(none)"

cat <<EOF
TAG=$TARGET_TAG
TAG_EXISTS=$TAG_EXISTS
TARGET_REF=$TARGET_REF
TAG_COMMIT=$TAG_COMMIT
TAG_DATE=$TAG_DATE
TAG_SUBJECT=$TAG_SUBJECT
PREVIOUS_TAG=$PREVIOUS_TAG
RANGE=$RANGE
INITIAL_RELEASE=$INITIAL_RELEASE
HEAD_COMMIT=$HEAD_COMMIT
HEAD_DATE=$HEAD_DATE
HEAD_SUBJECT=$HEAD_SUBJECT
BRANCH=$BRANCH
DIRTY=$DIRTY

[COMMITS]
$COMMITS

[STAT]
$STAT
EOF
