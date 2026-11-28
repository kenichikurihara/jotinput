#!/usr/bin/env bash
# scripts/pr_ship.sh - Helper for JotInput's mandatory topic-branch + PR + pre-merge CI workflow.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

usage() {
    cat << 'EOF'
Usage:
  ./scripts/pr_ship.sh status
  ./scripts/pr_ship.sh open --title "<PR title>" [--body-file <path>]
  ./scripts/pr_ship.sh merge
EOF
}

CMD="${1:-}"
if [ -z "$CMD" ]; then
    usage
    exit 1
fi
shift

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

case "$CMD" in
    status)
        echo "Branch: $CURRENT_BRANCH"
        git status -s
        if [ "$CURRENT_BRANCH" != "main" ]; then
            PR_NUM=$(gh pr view --json number --jq '.number' 2>/dev/null || true)
            if [ -n "$PR_NUM" ]; then
                echo "Open PR: #$PR_NUM"
                gh pr checks "$PR_NUM" || true
            else
                echo "No open PR for branch '$CURRENT_BRANCH'."
            fi
        fi
        ;;

    open)
        TITLE=""
        BODY_FILE=""
        while [ $# -gt 0 ]; do
            case "$1" in
                --title)
                    TITLE="${2:-}"
                    shift 2
                    ;;
                --body-file)
                    BODY_FILE="${2:-}"
                    shift 2
                    ;;
                *)
                    usage
                    exit 1
                    ;;
            esac
        done

        if [ "$CURRENT_BRANCH" = "main" ] || [ "$CURRENT_BRANCH" = "HEAD" ]; then
            echo "Error: Must be on a topic branch (not '$CURRENT_BRANCH') before opening a PR."
            exit 1
        fi
        if [ -n "$(git status --porcelain)" ]; then
            echo "Error: Working tree has uncommitted changes. Commit or stash before shipping."
            exit 1
        fi

        echo "=== Pushing branch '$CURRENT_BRANCH' ==="
        git push -u origin "$CURRENT_BRANCH"

        EXISTING_PR=$(gh pr list --head "$CURRENT_BRANCH" --base main --state open --json number --jq '.[0].number // empty')
        if [ -n "$EXISTING_PR" ]; then
            echo "Reusing existing open PR #$EXISTING_PR"
            PR_NUM="$EXISTING_PR"
        else
            if [ -z "$TITLE" ]; then
                TITLE="$(git log -1 --pretty=%s)"
            fi
            CREATE_ARGS=(--base main --head "$CURRENT_BRANCH" --title "$TITLE")
            if [ -n "$BODY_FILE" ]; then
                CREATE_ARGS+=(--body-file "$BODY_FILE")
            else
                CREATE_ARGS+=(--fill)
            fi
            PR_URL=$(gh pr create "${CREATE_ARGS[@]}")
            echo "Created PR: $PR_URL"
            PR_NUM=$(gh pr view --json number --jq '.number')
        fi

        echo "=== Watching PR #$PR_NUM checks ==="
        gh pr checks "$PR_NUM" --watch
        ;;

    merge)
        if [ "$CURRENT_BRANCH" = "main" ] || [ "$CURRENT_BRANCH" = "HEAD" ]; then
            echo "Error: Run 'merge' while checked out on the PR topic branch."
            exit 1
        fi

        PR_JSON=$(gh pr view --json number,state,baseRefName,url 2>/dev/null || true)
        if [ -z "$PR_JSON" ]; then
            echo "Error: No PR found for branch '$CURRENT_BRANCH'."
            exit 1
        fi
        PR_NUM=$(printf '%s' "$PR_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["state"]=="OPEN" and d["baseRefName"]=="main", d; print(d["number"])')

        echo "=== Verifying Build & Test gate on PR #$PR_NUM ==="
        CHECKS_JSON=$(gh pr view "$PR_NUM" --json statusCheckRollup)
        python3 - "$CHECKS_JSON" << 'EOF'
import json, sys
data = json.loads(sys.argv[1])
rollup = data.get("statusCheckRollup") or []
gate = [c for c in rollup if c.get("name") == "Build & Test"]
if not gate:
    print("ERROR: Required check 'Build & Test' not found yet.", file=sys.stderr)
    sys.exit(1)
c = gate[0]
if c.get("status") != "COMPLETED" or c.get("conclusion") != "SUCCESS":
    print(f"ERROR: 'Build & Test' is not green: status={c.get('status')} conclusion={c.get('conclusion')}", file=sys.stderr)
    sys.exit(1)
print("OK: 'Build & Test' check is COMPLETED / SUCCESS.")
EOF

        echo "=== Merging PR #$PR_NUM (admin squash + delete branch) ==="
        gh pr merge "$PR_NUM" --admin --squash --delete-branch

        echo "=== Syncing main and locating post-merge Android CI run ==="
        git checkout main
        git pull --ff-only origin main
        HEAD_SHA=$(git rev-parse HEAD)

        RUN_ID=""
        for _ in $(seq 1 12); do
            RUN_ID=$(gh run list --workflow "Android CI" --branch main --commit "$HEAD_SHA" --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)
            if [ -n "$RUN_ID" ]; then
                break
            fi
            sleep 2
        done

        if [ -n "$RUN_ID" ]; then
            echo "=== Watching main Android CI run #$RUN_ID ==="
            gh run watch "$RUN_ID" --exit-status
        else
            echo "Notice: No Android CI run triggered for $HEAD_SHA (e.g. paths-ignore match)."
        fi
        ;;

    *)
        usage
        exit 1
        ;;
esac
