#!/usr/bin/env bash

apt-get update

# Pieces that I need for sanity
apt-get install -y vim
apt-get install -y tmux
apt-get install -y emacs
apt-get install -y zsh
apt-get install -y git

# Clojure just doesn't get very far without this
apt-get install -y openjdk-7-jdk

mkdir /home/vagrant/bin
# Pieces needed to build the libraries I'm downloading shortly
apt-get install -y libtool
apt-get install -y autoconf
# TODO: Why does autogen.sh ignore this?
apt-get install -y gettext

echo "export PATH=$PATH:/home/vagrant/bin" >> /home/vagrant/.bashrc

wget https://raw.github.com/technomancy/leiningen/stable/bin/lein -O /home/vagrant/bin/lein
chmod u+x /home/vagrant/bin/lein
# This makes sense if this script is running as the vagrant user.
# Note so much if it's some sort of generic cloud-init sort of situation
lein

mkdir projects
cd projects

# Don't have the ssh connection set up. It would really be pretty dumb to do
# so. 
# If I want to do dev work inside the VM (where it belongs), this story
# changes.

mkdir base
cd base
git clone https://github.com/jimrthy/libsodium.git
git clone https://github.com/jimrthy/libzmq.git
git clone https://github.com/jimrthy/jzmq.git

cd ..
mkdir frereth
cd frereth
git clone https://github.com/jimrthy/cljeromq.git
# Realistically, these next 3 pieces belong on totally separate VMs
# For now, I truly do want them to be running on the same machine.
# At least part of the problem I'm having that led to this particular
# rabbit hole is that I don't really trust what's installed on my dev
# machine.
git clone https://github.com/jimrthy/frereth-renderer.git
git clone https://github.com/jimrthy/frereth-client.git
# TODO: Add this next one in soon.
#git clone https://github.com/jimrthy/frereth-server.git

