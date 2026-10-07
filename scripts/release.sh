#!/usr/bin/env bash
# Releases Ephemeris: builds and tests a commit in a throwaway worktree, tags it v<version>-mc<minecraft>,
# keeps the Fabric jar under dist/<version>/, and pushes the tag.
#
#   scripts/release.sh <version> [<commit>]     the commit defaults to HEAD
#   scripts/release.sh --offline <version> ...  never asks origin anything; the tag stays local
#
# Nothing leaves the machine before it asks. A failure before then removes the tag it made and leaves the
# worktree under build/release/<version>/ for a look.
set -euo pipefail

offline=false
if [[ "${1:-}" == "--offline" ]]; then offline=true; shift; fi
version="${1:?usage: scripts/release.sh [--offline] <version> [<commit>]}"
commit_name="${2:-HEAD}"

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

fail() { echo "release: $*" >&2; exit 1; }

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$version' is not a version like 0.1.0"
minecraft="$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' libs.versions.toml | head -1)"
[[ -n "$minecraft" ]] || fail "no minecraft version in libs.versions.toml"
tag="v${version}-mc${minecraft}"
commit="$(git rev-parse --verify "${commit_name}^{commit}")" || fail "no commit '$commit_name'"

# Greater than the last release for this Minecraft version, and not taken here or on origin.
last="$(git tag --list "v*-mc${minecraft}" | sed "s/^v//; s/-mc.*//" | sort -V | tail -1)"
if [[ -n "$last" ]]; then
    newest="$(printf '%s\n%s\n' "$last" "$version" | sort -V | tail -1)"
    [[ "$newest" == "$version" && "$last" != "$version" ]] || fail "$version is not after the last release, $last"
fi
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists here"
if ! $offline; then
    remote_tags="$(git ls-remote --tags origin "refs/tags/$tag")" ||
        fail "origin cannot be reached; fix the remote or pass --offline"
    [[ -z "$remote_tags" ]] || fail "$tag already exists on origin"
fi

if [[ -f .sdkmanrc ]]; then
    pinned="$(sed -n 's/^java=//p' .sdkmanrc)"
    [[ -n "$pinned" && -d "$HOME/.sdkman/candidates/java/$pinned" ]] && export JAVA_HOME="$HOME/.sdkman/candidates/java/$pinned"
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

work="$root/build/release/$version"
[[ -e "$work" ]] && fail "$work exists from an earlier attempt; remove it and run 'git worktree prune'"

git tag -a "$tag" "$commit" -m "Ephemeris $version for Minecraft $minecraft"
pushed=false
cleanup() { $pushed || git tag -d "$tag" >/dev/null 2>&1 || true; }
trap cleanup EXIT

git worktree add --detach "$work" "$tag" >/dev/null
echo "release: building $tag ($(git rev-parse --short "$commit")) in $work"
(cd "$work" && ./gradlew build --console=plain -q)

expected="${version}+${minecraft}"
jar="$work/fabric/build/libs/ephemeris-fabric-${minecraft}-${expected}.jar"
[[ -f "$jar" ]] || fail "no $jar; the build did not read the tag"
built="$(unzip -p "$jar" fabric.mod.json | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -1)"
[[ "$built" == "$expected" ]] || fail "the jar says $built, not $expected"

dist="$root/dist/$version"
mkdir -p "$dist"
cp "$jar" "$dist/"

echo
echo "  Ephemeris $expected"
echo "  commit    $(git log -1 --format='%h %s' "$commit")"
echo "  jar       $dist/$(basename "$jar")"
echo
if $offline; then
    pushed=true
    echo "release: tagged $tag locally (offline; push it with 'git push origin $tag')"
else
    read -r -p "Push $tag to origin? [y/N] " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || fail "not pushed; nothing left the machine"
    git push origin "$tag"
    pushed=true
    echo "release: pushed $tag"
fi
git worktree remove --force "$work"
