#!/usr/bin/env bash

apt-get update

apt-get install -y vim
apt-get install -y tmux
apt-get install -y emacs
apt-get install -y zsh
apt-get install -y git
apt-get install -y openjdk-7-jdk

echo "export PATH=$PATH:~/bin" >> ~/.bashrc

wget https://raw.github.com/technomancy/leiningen/stable/bin/lein -O ~/bin/lein

git clone git:github.com:jimrthy/libsodium.git
git clone git:github.com:jimrthy/libzmq.git
git clone git:github.com:jimrthy/jzmq.git
git clone git:github.com:jimrthy/cljeromq.git
# Realistically, these next 3 pieces belong on totally separate VMs
# For now, I truly do want them to be running on the same machine.
# At least part of the problem I'm having that led to this particular
# rabbit hole is that I don't really trust what's installed on my dev
# machine.
git clone git:github.com:jimrthy/frereth-renderer.git
git clone git:github.com:jimrthy/frereth-client.git
# TODO: Add this next one in soon.
#git clone git:github.com:jimrthy/frereth-server.git

