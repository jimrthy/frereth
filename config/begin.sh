#! /bin/bash

echo "This isn't really runnable yet. It's just a template"
exit -1

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
# Q: How much trouble to set up a vivid container template?
lxc-create -t download -n baseline -- -d ubuntu -r trusty -a amd64

# This is needed for the way GLFW binds input devices.
# TODO: Update baseline's config file with the following line:
# Or maybe not...it really doesn't make any sense to try to run the
# renderer inside an LXC which, on most systems, will be running inside
# a VM.
# The entire point of this renderer is/will be to get something resembling
# bare-metal performance out of java
lxc.mount.entry = /dev/input/mice dev/input/mice none bind,create=file 0 0

# Clone it, so we have something to install into
# This extra step would work much better on btrfs.
# Really, this was to ease the pain of re-downloading
# the baseline container every time upstream changed.
# It's a holdover from my first days using ansible,
# when we didn't want the base containers to change unless
# it was absolutely necessary.
# You can probably skip this step with impunity
lxc-clone -o baseline -n installable

# Start it up in the background
lxc-start -d -n installable
# (I used to forget the -d a lot...when you make the same mistake,
# I recommend just running 'shutdown -h now' since you'll be running as root)

# Set up whichever user's going to be doing the installing
lxc-attach -n installable -- useradd -m james
# These next few lines seem like they should work,
# but they don't:
#lxc-attach -n installable -- echo "abc123\nabc123" | passwd james
#lxc-attach -n installable -- echo "abc123\nabc123" | (passwd --stdin james)
#lxc-attach -n installable -- echo "abc123:james" | chpasswd 
# So just break down and accept some interactivity for now
lxc-attach -n installable -- passwd james

# TODO: Move this into a local play

# Really only need this if you're caching/cloning the baseline image. If you're
# using something downloaded recently, this should be pointless.
lxc-attach -n installable -- apt-get update
# Install enough packages to allow ansible to run
# You can use one of ansible's really low-level commands to
# install python, but we have to get sshd installed to use ansible in the first
# place, and this approach is easier/simpler
lxc-attach -n installable -- apt-get install -y python openssh-server

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
# These steps weren't horrible, but this is a good place to
# switch to a different sandbox and possibly make a backup.
lxc-clone -o installable -n frereth

# TODO: Start here

# And now let's do the install
lxc-start -d -n frereth
# Get the IP address so you can update the hosts file
# TODO: Really should find time to finish writing a util
# to pick this up automatically.
lxc-ls --fancy
# Don't want to stop it:
# Want it running for when we kick off the "real" install,
# using setup
#lxc-stop -n frereth

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

