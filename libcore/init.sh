#!/bin/bash

export GOWORK=off

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

# The sing-box submodule is bumped frequently and each bump pulls newer
# transitive deps than libcore/go.mod pins. Go's default -mod=readonly then
# aborts the gomobile build with "updates to go.mod needed; run go mod tidy"
# (fine locally off a warm module cache, but fatal on a fresh CI checkout).
# Resync go.mod/go.sum against the checked-out submodule before building.
go mod tidy || exit 1

# gomobile init installs gobind@latest internally, bypassing go.mod. Android
# bind does not need its optional OpenAL setup, so build the pinned gobind tool
# directly and keep it local to this checkout.
mkdir -p .build/bin
go build -o .build/bin/gobind github.com/sagernet/gomobile/cmd/gobind
