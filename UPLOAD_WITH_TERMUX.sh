#!/data/data/com.termux/files/usr/bin/bash
# Run from the extracted JamGuard_Mobile folder. No bot tokens are needed here.
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="${1:-$HOME/Jam-guard-}"
if ! command -v git >/dev/null; then
  echo 'Install Git first: pkg install git'; exit 1
fi
if [[ ! -d "$repo_dir/.git" ]]; then
  git clone git@github.com:joh12121wwww/Jam-guard-.git "$repo_dir"
fi
if [[ "$source_dir" == "$(cd "$repo_dir" && pwd)" ]]; then
  echo 'Extract this package outside your Git checkout, then run the helper again.'; exit 1
fi
if [[ -n "$(git -C "$repo_dir" status --porcelain)" ]]; then
  echo 'Your Git checkout contains uncommitted work. Commit or back it up first:'
  git -C "$repo_dir" status --short
  exit 1
fi
branch="$(git -C "$repo_dir" branch --show-current)"
if [[ "$branch" != main ]]; then
  echo "Current branch is $branch. Switch to main after preserving your work, then rerun."
  exit 1
fi
git -C "$repo_dir" pull --ff-only origin main
# Replace only app sources/build definition. Git retains the full previous version.
# Existing unrelated files remain in the repository.
rm -rf -- "$repo_dir/app/src"
cp -a -- "$source_dir/app/src" "$repo_dir/app/src"
for item in build.gradle settings.gradle gradle.properties gradlew gradlew.bat gradle app/build.gradle README.md docs tests UPLOAD_WITH_TERMUX.sh; do
  if [[ -e "$source_dir/$item" ]]; then
    mkdir -p -- "$(dirname -- "$repo_dir/$item")"
    if [[ -d "$source_dir/$item" ]]; then
      mkdir -p -- "$repo_dir/$item"
      cp -a -- "$source_dir/$item/." "$repo_dir/$item/"
    else
      cp -a -- "$source_dir/$item" "$repo_dir/$item"
    fi
  fi
done
mkdir -p -- "$repo_dir/.github/workflows"
cp -a -- "$source_dir/.github/workflows/android.yml" "$repo_dir/.github/workflows/android.yml"
git -C "$repo_dir" add -- app build.gradle settings.gradle gradle.properties gradlew gradlew.bat gradle .github/workflows/android.yml README.md docs tests UPLOAD_WITH_TERMUX.sh
if git -C "$repo_dir" diff --cached --quiet; then
 echo 'No changes to upload.'; exit 0
fi
git -C "$repo_dir" diff --cached --stat
git -C "$repo_dir" commit -m 'Build JamGuard 2 mobile app with Telegram gateway and local incident reporting'
git -C "$repo_dir" push origin main
echo 'Uploaded. Open GitHub > Jam-guard- > Actions > Build JamGuard APK > Artifacts.'
