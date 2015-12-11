#! /bin/bash

# Generate GPG key for later upload
# TODO: Merge that into the config file
# UUID=`python -c 'import uuid; print str(uuid.uuid4())'`
UUID=6242e3cb-5934-4eb9-9470-f64d50a39747

# This next part's slow...don't do it often
# Especially on a VM
# It's needed to sign packages built/deployed by maven
# Since this is strictly a dev environment, you shouldn't need to be
# too horribly paranoid about it
# TODO: Regen the gpg-rules w/ a new UUID as needed, then export that same UUID
gpg2 --batch --gen-key roles/frereth-hacker/files/gpg-rules
gpg2 --export-secret-key --armor thisisfake+$UUID@nowhere.com > roles/frereth-hacker/files/hacker-priv_key.asc
gpg2 --armor --export thisisfake+$UUID@nowhere.com > roles/frereth-hacker/files/hacker-pub_key.asc


