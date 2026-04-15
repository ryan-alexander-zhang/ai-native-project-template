#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$repo_root/skills/toad-copilot"
target_dir="$repo_root/.agents/skills/toad-copilot"
link_target="../../skills/toad-copilot"

if [ ! -d "$source_dir" ]; then
  echo "Missing source directory: $source_dir" >&2
  exit 1
fi

mkdir -p "$(dirname "$target_dir")"

if [ -L "$target_dir" ]; then
  current_target="$(readlink "$target_dir")"
  if [ "$current_target" = "$link_target" ]; then
    echo "$target_dir already points to $link_target"
    exit 0
  fi
  echo "Refusing to replace existing symlink: $target_dir -> $current_target" >&2
  exit 1
fi

if [ -e "$target_dir" ]; then
  echo "Refusing to replace existing path: $target_dir" >&2
  exit 1
fi

ln -s "$link_target" "$target_dir"
echo "Linked $target_dir -> $link_target"
