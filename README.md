This repository contains all the experimental setup and scripts
used in the Arboreal paper.

#### Repository organization
This repository is organized as follows:
- The folder *deploy/tc* contains
  - The three scenarios used in the paper (nodes_*.txt) along with the resulting latencies between
    each pair of nodes (latencies_*.txt)
  - The scripts used inside each container to set up the TC rules, adding latency between nodes (setupTc*)
  - The IP addresses used for nodes and clients in the experiments (*Ips.txt)

- Each package (except the *utils* package) in the *src/main/kotlin* folder contains the code for a specific experiment in the paper. 
- The folder *deploy/configs* contains all configuration files, including:
  - A configuration file for each experiment, with the parameters used in the paper.
  - A configuration file for each of the three deployment scenarios used in the paper (tc_*.yaml)
  - Deployment-specific configuration for experiments with Arboreal (docker_config.yaml) and Cassandra (cass_docker_config.yaml)

#### Requirements
These scripts assume that:
- You are using a cluster of machines based on OAR (https://oar.imag.fr/), such as Grid5000 (https://www.grid5000.fr/).
- The docker engine is configured to allow TCP connections on port 2376 (the script *g5k-setup-docker* will set this up on Grid5000)
- (Optional) The machines in the cluster use a shared filesystem, such as NFS, to share the data between them.

#### Setup
- Copy the *"deploy"* folder from the Arboreal repository (https://github.com/pfouto/edge-tree) to the shared filesystem,
or alternatively, to each machine in the cluster.
- Copy the *"deploy"* folder from the Arboreal's client repository (https://github.com/pfouto/edge-client) to the shared filesystem,
or alternatively, to each machine in the cluster. Use a different folder than the one used for the Arboreal code.
- Clone this repository, and, in the folder *"deploy"*, build the required docker images:
  - Run `docker build  -t tc:0.1 . && docker save tc:0.1 -o tc.tar` to build the image used for both the client and the server of Arboreal, and save it to a tar file.
  - Run `docker build -f cassandra_tc.Dockerfile  -t cass_tc:0.1 . && docker save cass_tc:0.1 -o cass_tc.tar` to build the image used for the Cassandra nodes, and save it to a tar file.

- Copy the *"deploy"* folder (which now includes the .tar files) to the shared filesystem,
or alternatively, to each machine in the cluster. Use a different folder than the one used for the two previous ones.

