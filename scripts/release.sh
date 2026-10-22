#!/usr/bin/env bash
# Releases Ephemeris: builds and tests a commit in a throwaway worktree, tags it v<version>-mc<minecraft>,
# keeps the Fabric jar under dist/<version>/, pushes the tag and publishes the jar as a GitHub release asset,
# which is where the pack of a mod that depends on it names it.
#
#   scripts/release.sh [<version>] [<commit>]     the commit defaults to HEAD
#   scripts/release.sh --offline [<version>] ...  never asks origin anything; the tag stays local
#
# Without a version it offers the next patch, minor and major after the last release, or takes one typed in.
#
# Nothing leaves the machine before it asks. A failure before then removes the tag it made and leaves the
# worktree under build/release/<version>/ for a look.
set -euo pipefail

offline=false
if [[ "${1:-}" == "--offline" ]]; then offline=true; shift; fi
version="${1:-}"
commit_name="${2:-}"
# A lone argument that is no version is the commit, with the version still to be asked for.
if [[ -n "$version" && -z "$commit_name" && ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    commit_name="$version"
    version=""
fi
commit_name="${commit_name:-HEAD}"

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

fail() { echo "release: $*" >&2; exit 1; }

minecraft="$(sed -n 's/^minecraft *= *"\(.*\)"/\1/p' libs.versions.toml | head -1)"
[[ -n "$minecraft" ]] || fail "no minecraft version in libs.versions.toml"
last="$(git tag --list "v*-mc${minecraft}" | sed "s/^v//; s/-mc.*//" | sort -V | tail -1)"

# The next patch, minor or major after the last release, or any version typed in.
ask_for_version() {
    local major minor patch answer typed
    IFS=. read -r major minor patch <<< "${last:-0.0.0}"
    local presets=("$major.$minor.$((patch + 1))" "$major.$((minor + 1)).0" "$((major + 1)).0.0")
    local kinds=(patch minor major)
    if [[ -n "$last" ]]; then echo "The last release is $last." >&2; else echo "Nothing has been released yet." >&2; fi
    for index in 0 1 2; do echo "  $((index + 1))) ${presets[$index]}  ${kinds[$index]}" >&2; done
    echo "  4) another version" >&2
    while true; do
        read -r -p "Release which version? " answer || fail "no version given, and no terminal to ask for one"
        case "$answer" in
            1|2|3) echo "${presets[$((answer - 1))]}"; return ;;
            4) read -r -p "Version: " typed || fail "no version given, and no terminal to ask for one" ;;
            *) typed="$answer" ;;
        esac
        if [[ "$typed" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo "$typed"; return; fi
        echo "'$typed' is neither a choice nor a version like 0.1.0" >&2
    done
}
[[ -n "$version" ]] || version="$(ask_for_version)"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$version' is not a version like 0.1.0"
tag="v${version}-mc${minecraft}"
commit="$(git rev-parse --verify "${commit_name}^{commit}")" || fail "no commit '$commit_name'"

# Greater than the last release for this Minecraft version, and not taken here or on origin.
if [[ -n "$last" ]]; then
    newest="$(printf '%s\n%s\n' "$last" "$version" | sort -V | tail -1)"
    [[ "$newest" == "$version" && "$last" != "$version" ]] || fail "$version is not after the last release, $last"
fi
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists here"
if ! $offline; then
    remote_tags="$(git ls-remote --tags origin "refs/tags/$tag")" ||
        fail "origin cannot be reached; fix the remote or pass --offline"
    [[ -z "$remote_tags" ]] || fail "$tag already exists on origin"
    # The release is published as whoever gh is acting as, which is not who git pushes as.
    slug="$(git remote get-url origin | sed -E 's#.*github\.com[:/]([^/]+/[^/]+)$#\1#; s#\.git$##')"
    account="$(gh api user -q .login 2>/dev/null)" || fail "gh is not logged in: run 'gh auth login'"
    [[ "$(gh api "repos/$slug" -q .permissions.push 2>/dev/null)" == "true" ]] ||
        fail "gh is acting as $account, who cannot publish to $slug: run 'gh auth switch -u ${slug%%/*}' first"
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
    read -r -p "Push $tag to origin and publish the jar as a release? [y/N] " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || fail "not pushed; nothing left the machine"
    git push origin "$tag"
    pushed=true
    echo "release: pushed $tag"
    # A `+` in an asset's name is not kept the same by every host, so the asset has none.
    asset="$dist/assets/$(basename "${jar//+/-}")"
    mkdir -p "$dist/assets"
    cp "$jar" "$asset"
    slug="$(git remote get-url origin | sed -E 's#.*github\.com[:/]([^/]+/[^/]+)$#\1#; s#\.git$##')"
    if gh release view "$tag" --repo "$slug" >/dev/null 2>&1; then
        gh release upload "$tag" "$asset" --repo "$slug" --clobber
    else
        gh release create "$tag" "$asset" --repo "$slug" --verify-tag --prerelease --title "Ephemeris $expected" \
            --notes "Ephemeris $expected for Minecraft $minecraft."
    fi
    echo "release: published $tag"
fi
git worktree remove --force "$work"
