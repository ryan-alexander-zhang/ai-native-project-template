#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
source_root="$repo_root/skills/toad-copilot"
target_root="$repo_root/.agents/skills"
legacy_target_dir="$target_root/toad-copilot"
legacy_link_target="../../skills/toad-copilot"

if [ ! -d "$source_root" ]; then
  echo "Missing source directory: $source_root" >&2
  exit 1
fi

mkdir -p "$target_root"

if [ -L "$legacy_target_dir" ]; then
  current_target="$(readlink "$legacy_target_dir")"
  if [ "$current_target" = "$legacy_link_target" ]; then
    rm "$legacy_target_dir"
    echo "Removed legacy aggregate symlink: $legacy_target_dir"
  else
    echo "Refusing to replace existing symlink: $legacy_target_dir -> $current_target" >&2
    exit 1
  fi
elif [ -e "$legacy_target_dir" ]; then
  echo "Refusing to replace existing path: $legacy_target_dir" >&2
  exit 1
fi

shopt -s nullglob
skill_files=("$source_root"/*/SKILL.md)
shopt -u nullglob

if [ "${#skill_files[@]}" -eq 0 ]; then
  echo "No skills found under $source_root" >&2
  exit 1
fi

linked_count=0

for skill_file in "${skill_files[@]}"; do
  skill_dir="$(dirname "$skill_file")"
  skill_name="$(basename "$skill_dir")"
  target_dir="$target_root/$skill_name"
  link_target="../../skills/toad-copilot/$skill_name"

  if [ -L "$target_dir" ]; then
    current_target="$(readlink "$target_dir")"
    if [ "$current_target" = "$link_target" ]; then
      echo "$target_dir already points to $link_target"
      continue
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
  linked_count=$((linked_count + 1))
done

echo "Installed $linked_count toad-copilot skills"
