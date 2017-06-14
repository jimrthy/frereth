#! /bin/bash

echo "This isn't really runnable yet. It's just a template"
exit -1

# HOWTO setup the baseline appliance so we can run an install on it
# You may have issues running this as a normal user. At this current
# point in time, you really need at least lxc 1.0.
#
# That's much less onerous now than it was when I started writing this.

# Note that there are still some requirements to be able to do this.
# My notes about this stem from:
# https://www.stgraber.org/2014/01/17/lxc-1-0-unprivileged-containers/

# That's pretty seriously out of date.

# Really, all I had to do was make my home directory
# world-executable (so it might be an excellent idea to set up a
# user account with no purpose in life except to run containers...
# although that really sounds like a basic "best practice" anyway),
# set up the ~/.config/lxc/default.conf (which might possibly be
# optional, but I doubt it), and add myself to /etc/lxc/lxc-usernet

# There are issuse with sudo'ing in an unprivileged container.
# I haven't been able to track down examples of the affecting anyone else.
# I can do it when I'm using the btrfs backing store. I can't on
# the simple directory backing store.

# TODO: Need to update these scripts to deal with that.
# For that matter, I need to update them to work with Ansible 2.0 

# Get the bare configuration downloaded
# Note that you might very well be happier with the 32-bit version for this,
# depending.
lxc-create -t download -n installable -B btrfs -- -d ubuntu -r xenial -a amd64

# This is needed for the way GLFW binds input devices.
# TODO: Update baseline's config file with the following line:
# Or maybe not...it really doesn't make any sense to try to run the
# renderer inside an LXC which, on most systems, will be running inside
# a VM.
# The entire point of the renderer is to push the performance envelope
# while running on something completely ridiculous, like a web browser
# or the JVM.
# Graphics acceleration should be available in both those scenarios.
# It isn't in this one.
# So, really, this is a Bad Idea(TM)
#lxc.mount.entry = /dev/input/mice dev/input/mice none bind,create=file 0 0

# Start it up
lxc-start -d -n installable
# Background mode is the default now. I don't know when that changed.
# Keep the -d around in case you're running this on a distro that keeps
# ancient software versions around forever.
# Forgetting it when you need it and starting it it foreground mode can
# get really frustrating.

# Set up whichever user's going to be doing the installing
# This might still make sense if you're using privileged LXC's.
# Those seem like such a horrible idea that it's tough to imagine why
# anyone would.
# If you can't run sudo, there's no point to adding this user.
# You'll have to do this entire thing manually as root.
lxc-attach -n installable -- useradd -m builder

# Setting/changing the password programatically is problematic
# and seems to get broken regularly with new releases.
# So just break down and accept some interactivity for now
# Actually, this really isn't a viable option anyway.
# Need to set up root login through SSH
# TODO: Automate that
# This is supposed to be trivial, using an expect script
# TODO: More importantly: disable it when I'm finished
lxc-attach -n installable -- passwd builder

# TODO: Move this into a local playbook

# Really only need this if you're caching/cloning the baseline image. If you're
# using something downloaded recently, this should be pointless.
lxc-attach -n installable -- apt-get update
# Install enough packages to allow ansible to run
# You can use one of ansible's really low-level commands to
# install python, but we have to get sshd installed to use ansible in the first
# place, and this approach is easier/simpler
lxc-attach -n installable -- apt-get install -y python python-apt python-pexpect openssh-server

# Some pieces shouldn't happen while it's running
lxc-stop -n installable

# Switch to another copy for doing the actual work.
# These steps weren't horrible, but this is a good place to
# switch to a different sandbox and possibly make a backup.
lxc-clone -o installable -n frereth

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
