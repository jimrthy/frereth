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
# Strictly speaking, this next package is optional. But getting 0mq
# man pages is nice.
# Not nice enough to be worth the massive download. This thing's
# more ridiculous than emacs.
#apt-get install -y asciidoc
apt-get install -y g++

echo "export PATH=$PATH:/home/vagrant/bin" >> /home/vagrant/.bashrc

wget https://raw.github.com/technomancy/leiningen/stable/bin/lein -O /home/vagrant/bin/lein
chmod u+x /home/vagrant/bin/lein
# This makes sense if this script is running as the vagrant user.
# Note so much if it's some sort of generic cloud-init sort of situation
/home/vagrant/bin/lein

mkdir projects
cd projects

# Don't have the ssh connection set up. It would really be pretty dumb to do
# so. 
# If I want to do dev work inside the VM (where it belongs), this story
# changes.

mkdir base
cd base

# Here's where trying this as shell scripts really falls apart at the seams:
# The basic user should be doing everything except the install.
# Then again, this is all about the dev user anyway. So who cares?
# (That's a really horrible attitude to take, but I have bigger fish to fry
# at the moment)
git clone https://github.com/jimrthy/libsodium.git
cd libsodium
./autogen.sh
./configure
make && make check && make install
cd ..

git clone https://github.com/jimrthy/zeromq4-x.git
cd zeromq4-x
./autogen.sh
./configure
# This part seems to be failing miserably and sending me into an infinite loop.
# Considering all the other moving parts I'm dealing with, I'm starting to
# strongly suspect that I should be using a different upstream repo here
# Yeah...
make 
# that definitely causes some serious issues. I should not be trying to deal
# with whatever's happening in this repo. It's making it impossible to deal
# with basic Curve stuff.
make check && make install
cd ..

# Building this actually gets pretty tricky
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

