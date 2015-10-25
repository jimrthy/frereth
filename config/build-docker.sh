#! /bin/bash

# Note that this is horribly inefficient. Really need to clean up all the
# stuff that we won't actually be using
sudo tar -C $HOME/.local/share/lxc/frereth-work/rootfs -cz . -f frereth-worker.tar.gz

# Q: Does this actually work?
sudo cat frereth-worker.tar.gz | docker import - frereth/development

