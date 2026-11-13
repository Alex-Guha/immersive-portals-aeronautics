#!/usr/bin/env bash
# Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
# Refuses commits that would put machine details into this public repository: added lines and commit messages naming
# a home directory or a Windows user folder, an email address in a message other than a noreply one, and (with
# --identities, for the maintainer's own pushes) an author or committer email other than a GitHub noreply address.
# A backstop for CI; the maintainer's own commit hooks check more.
#
#   scripts/check-commits.sh [--identities] <rev-list arguments>       e.g. origin/main..HEAD
set -euo pipefail
PATTERNS='/home/[a-z_][a-z0-9_-]*/|[A-Za-z]:[/\\]+Users[/\\]+[^<]|/Users/[a-z]|/mnt/[a-z]/|[.]ssh/'
EMAIL='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
NOREPLY='@users\.noreply\.github\.com|noreply@'
identities=
[ "${1:-}" = --identities ] && { identities=1; shift; }
status=0
for commit in $(git rev-list "$@"); do
    short=$(git rev-parse --short "$commit")
    added=$(git diff-tree -p --no-commit-id --root --no-color -U0 --diff-filter=ACMR "$commit" \
        | awk '/^\+\+\+ /{file=substr($0, 7); next} /^\+/{print file ": " substr($0, 2)}')
    message=$(git log -1 --format=%B "$commit")
    hits=$( { printf '%s\n' "$added" | grep -E "$PATTERNS" || true
              printf '%s\n' "$message" | grep -E "$PATTERNS" | sed 's/^/message: /' || true
              printf '%s\n' "$message" | grep -E "$EMAIL" | grep -vE "$NOREPLY" | sed 's/^/message: /' || true
              [ -z "$identities" ] || git log -1 --format='author: %ae%ncommitter: %ce' "$commit" | grep -vE '@users\.noreply\.github\.com$|: noreply@github\.com$' || true; } )
    if [ -n "$hits" ]; then
        echo "commit $short adds what looks like a machine detail or a personal address:"
        printf '%s\n' "$hits" | sed 's/^/  /'
        status=1
    fi
done
[ $status -eq 0 ] && echo "$(git rev-list --count "$@") commit(s): nothing machine-specific"
exit $status
