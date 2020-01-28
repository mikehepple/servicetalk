#!/bin/bash
#
# Copyright © 2019 Apple Inc. and the ServiceTalk project authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -eu

cd "$(dirname "$0")"
cd ..

version=""
DOCS_FOLDER="docs/generation/.out/remote"
JAVADOC_FOLDER="build/javadoc"
BRANCH_NAME=$(git symbolic-ref -q HEAD)
BRANCH_NAME=${BRANCH_NAME##refs/heads/}
GIT_AUTHOR=$(git --no-pager show -s --format='%an <%ae>' HEAD)

function usage() {
cat << EOF
Run as:
publish-docs.sh - to update the SNAPSHOT version of docs website only
publish-docs.sh {release_version} - to publish docs for a new release version and update the SNAPSHOT version
EOF
}

function clean_up_gh_pages() {
  if git worktree list | grep -q gh-pages; then
    echo "Cleanup 'gh-pages' worktree"
    git worktree remove -f gh-pages
  fi
  if git branch --list | grep -q "^\s*gh-pages$"; then
    echo "Remove 'gh-pages' branch"
    git branch -Df gh-pages
  fi
  # Just in case of the undefined initial state make sure there is no gh-pages folder
  rm -rf gh-pages
}

if [ "$#" -eq "0" ]; then
    echo "Publishing docs website for the SNAPSHOT version only"
elif [ "$#" -eq "1" ]; then
    version="$1"
    if ( echo "$version" | grep -Eqv "^\d+\.\d+$" ); then
        echo "Release version should match 'major.minor' pattern"
        exit 1
    fi
    echo "Publishing docs website for the release version $version"
else
    usage
    exit 1
fi

echo ""

# Clean up the state at the beggining in case the previous run did not finish successfuly
clean_up_gh_pages

echo "Generate docs website"
pushd docs/generation
./gradlew --no-daemon clean validateRemoteSite
popd
echo "Docs website generated, see ./$DOCS_FOLDER"

echo "Generate javadoc"
./gradlew --no-daemon javadocAll
echo "Javadoc generated, see ./$JAVADOC_FOLDER"
``
if ( ! git remote get-url docs ); then
  git remote add docs git@github.com:apple/servicetalk.git
fi

git fetch docs +gh-pages:gh-pages
git worktree add gh-pages gh-pages

touch gh-pages/.nojekyll
\cp -r $DOCS_FOLDER/* gh-pages
echo "Copy javadoc to gh-pages/servicetalk/SNAPSHOT"
# Avoid accumulating old javadocs for classes that have been moved, renamed or deleted.
rm -rf gh-pages/servicetalk/SNAPSHOT/javadoc
\cp -r $JAVADOC_FOLDER gh-pages/servicetalk/SNAPSHOT
if [ ! -z "$version" ]; then
    echo "Copy javadoc to gh-pages/servicetalk/$version"
    rm -rf gh-pages/servicetalk/$version/javadoc
    \cp -r $JAVADOC_FOLDER gh-pages/servicetalk/$version
fi

pushd gh-pages
# Do not override older javadoc with Antora's placeholder:
git diff --name-only | grep 'javadoc/index.html' | grep -v $version | grep -v SNAPSHOT | xargs git checkout --

git add * .nojekyll
if [ -z "$version" ]; then
    git commit --author="$GIT_AUTHOR" -m "Update SNAPSHOT doc website"
else
    git commit --author="$GIT_AUTHOR" -m "Publish docs website $version"
fi

git push docs gh-pages
popd

# Clean up the state (worktree and temporary branch) after publication of the docs
clean_up_gh_pages

if [ -z "$version" ]; then
    echo "Docs website for the SNAPSHOT version successfully updated"
else
    echo "Docs website for the release version $version successfully published"
fi
