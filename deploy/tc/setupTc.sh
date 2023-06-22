#!/bin/sh

echo "I am $1 and there are $3 nodes"

idx=$1
latencyMap="/tc/$2"
n_nodes=$3
selfLatency=$4

out_bandwith=$5
in_bandwith=$5

ipsMap="tc/serverIps.txt"

#bandwith=$2


# Read ipsMap to list of ips
ips=""
while read -r ip; do
  ips="${ips} ${ip}"
done <"$ipsMap"

run_cmd() {
  echo "$1"
  eval $1
}

setup_tc() {

  run_cmd "modprobe ifb numifbs=1"
  run_cmd "ip link add ifb0 type ifb"
  run_cmd "ip link set dev ifb0 up"
  run_cmd "tc qdisc add dev eth0 handle ffff: ingress"
  run_cmd "tc filter add dev eth0 parent ffff: protocol ip u32 match u32 0 0 action mirred egress redirect dev ifb0"
  run_cmd "tc qdisc add dev ifb0 root handle 1: htb default 1"
  run_cmd "tc class add dev ifb0 parent 1: classid 1:1 htb rate ${in_bandwith}mbit"
  run_cmd "tc qdisc add dev eth0 root handle 1: htb"
  run_cmd "tc class add dev eth0 parent 1: classid 1:1 htb rate ${out_bandwith}mbit"

  j=0
  for n in $1; do
    if [ $j -eq $n_nodes ]; then break; fi

    j=$((j + 1))

    targetIp=$(echo ${ips} | cut -d' ' -f${j})
    echo "--- $targetIp ->  $n"

    if [ $((j - 1)) -eq $idx ]; then
      run_cmd "tc class add dev eth0 parent 1: classid 1:${j}1 htb rate ${out_bandwith}mbit"
      run_cmd "tc qdisc add dev eth0 parent 1:${j}1 netem delay ${selfLatency}ms"
      run_cmd "tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst $targetIp flowid 1:${j}1"
    else
      run_cmd "tc class add dev eth0 parent 1: classid 1:${j}1 htb rate ${out_bandwith}mbit"
      run_cmd "tc qdisc add dev eth0 parent 1:${j}1 netem delay ${n}ms"
      run_cmd "tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst $targetIp flowid 1:${j}1"
    fi

  done
}

i=0
echo "Setting up tc emulated network..."
while read -r line; do
  if [ $idx -eq $i ]; then
    setup_tc "$line" "$i"
    break
  fi
  i=$((i + 1))
done <"$latencyMap"

echo "Done."

/bin/sh
