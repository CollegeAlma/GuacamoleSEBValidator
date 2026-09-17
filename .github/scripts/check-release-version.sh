#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo 'Usage: check-release-version.sh TAG MAVEN_VERSION' >&2
    exit 1
fi

tag=$1
version=$2
if [[ -z "$version" || "$version" == *SNAPSHOT* ]]; then
    echo 'Release version must be non-empty and must not be a SNAPSHOT.' >&2
    exit 1
fi
if [[ "$tag" != "v$version" ]]; then
    echo "Release tag '$tag' does not match Maven version '$version' (expected 'v$version')." >&2
    exit 1
fi
