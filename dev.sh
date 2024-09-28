#!/usr/bin/env sh
nix develop --impure path:. -c $SHELL
# N.B.
# --impure  - allows devenv to access state data when running with flakes
# path:.    - allows devenv to access a .env file (if it exists) when not in the repo
# -c $SHELl - support fish, zsh, etc. (default shell is bash)
