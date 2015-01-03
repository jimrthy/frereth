#! /bin/bash

# HOWTO setup the baseline appliance so we can run an install on it
# You may have issues running this as a normal user. At this current
# point in time, you really need lxc 1.0, which currently means
# ubuntu 14.
# If you don't feel like being quite that bleeding edge, you might
# be happier just running a privileged container as root.
# Note that there are still some requirements to get to this point,
# though they seem to be getting easier.
# https://www.stgraber.org/2014/01/17/lxc-1-0-unprivileged-containers/
# was my template for this.

# Although, really, all I had to do was make my home directory 
# world-executable (so it might be an excellent idea to set up a
# user account with no purpose in life except to run containers...
# although that really sounds like a basic "best practice" anyway),
# set up the ~/.config/lxc/default.conf (which might possibly be
# optional, but I doubt it), and add myself to /etc/lxc/lxc-usernet

# Note: this didn't actually work. sudo is broken in my unprivileged
# containers on my laptop. I'm torn between frustration that it works
# like a charm on my desktop and not caring. After all, that's just
# a very tiny 'nice to have' for what I'm trying to accomplish.

# Get the bare configuration downloaded
lxc-create -t download -n baseline -- -d ubuntu -r trusty -a amd64

# This is needed for the way GLFW binds input devices.
# TODO: Update baseline's config file with the following line:
lxc.mount.entry = /dev/input/mice dev/input/mice none bind,create=file 0 0

# Clone it, so we can set up a baseline to work with
lxc-clone -o baseline -n installable

# Start it up in the background
lxc-start -d -n installable
# (I used to forget the -d a lot...when you make the same mistake,
# I recommend just logging in as ubuntu/ubuntu and shutting it down)

# Set up whichever user's going to be doing the installing
lxc-attach -n installable -- useradd -m james
# These next couple of lines seem like they should work,
# but they don't:
#lxc-attach -n installable -- echo "abc123
#abc123" | passwd james
# So just break down and accept some interactivity for now
lxc-attach -n installable -- passwd james

# TODO: Move this into another play

# Install python
lxc-attach -n installable -- apt-get update
lxc-attach -n installable -- apt-get install -y python

# Some pieces shouldn't happen while it's running
lxc-stop -n installable

# Make james a password-less sudoer:
INSTALLABLE_ROOT=$HOME/.local/share/lxc/installable/rootfs
sudo cp resources/master $INSTALLABLE_ROOT/etc/sudoers.d/
sudo chmod 0400 $INSTALLABLE_ROOT/etc/sudoers.d/master
# Ownership of that file is problematic, because of
# the way user/group ids get mapped
# This happened to be the numbers on the system I
# developed this on
sudo chown 165536:165536 $INSTALLABLE_ROOT/etc/sudoers.d/master

# Switch to another copy for doing the actual work.
# These steps weren't horrible, but it's good to
# automate as much as humanly possible.
lxc-clone -o installable -n frereth

# And now let's do the install
lxc-start -d -n frereth
# Get the IP address so you can update the hosts file
# TODO: Really should find time to finish writing a util
# to pick this up automatically.
lxc-ls --fancy

# Now install and configure ansible (on the machine
# the will be configuring the VMs that will do the actual work),
# and you should be ready to begin
# The pre-reqs for that step look at least vaguely along
# these lines:
sudo apt-get install python-dev libgmp-dev sshpass
# It would be better to set up ssh keys and something like
# ssh-agent than using a password

mkvirtualenv ansible
pip install -r requirements.pip
# Note to self: really should make the virtualenv set up
# the ansible environment automatically, during activate

