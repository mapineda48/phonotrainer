#!/bin/sh
# Installs the repository hygiene hooks (architecture 20.7):
#   pre-commit  runs check_repo.py --staged     (the staged files)
#   pre-push    runs check_repo.py --pre-push   (every commit of the pushed range)
#
# Usage: android/scripts/hygiene/install-hooks.sh [--force]
#
# The hooks go to `git rev-parse --git-path hooks`, which honours core.hooksPath. Git shares that directory between
# all worktrees of a repository, so each hook runs the check_repo.py of the worktree it fires in and does nothing in a
# checkout that has no Android client. An existing hook that this script did not write is kept unless --force is
# given, in which case it is moved aside to <hook>.pre-hygiene.
set -eu

marker="phonotrainer-hygiene-hook"
force=0
for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "install-hooks.sh: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"
hooks_dir=$(git rev-parse --git-path hooks)
mkdir -p "$hooks_dir"

write_hook() {
  name=$1
  mode=$2
  target="$hooks_dir/$name"
  if [ -e "$target" ] && ! grep -q "$marker" "$target"; then
    if [ "$force" -eq 1 ]; then
      mv "$target" "$target.pre-hygiene"
      echo "install-hooks.sh: moved the existing $name hook to $name.pre-hygiene"
    else
      echo "install-hooks.sh: $target exists and was not written by this script; rerun with --force" >&2
      exit 1
    fi
  fi
  cat > "$target" <<EOF
#!/bin/sh
# $marker: installed by android/scripts/hygiene/install-hooks.sh; runs the repository hygiene check.
top=\$(git rev-parse --show-toplevel) || exit 1
script="\$top/android/scripts/hygiene/check_repo.py"
[ -f "\$script" ] || exit 0
if ! command -v python3 >/dev/null 2>&1; then
  echo "$name: python3 is required by the hygiene check (android/scripts/hygiene)" >&2
  exit 1
fi
exec python3 "\$script" $mode
EOF
  chmod +x "$target"
  echo "install-hooks.sh: installed $name"
}

write_hook pre-commit "--staged"
write_hook pre-push '--pre-push "$@"'
