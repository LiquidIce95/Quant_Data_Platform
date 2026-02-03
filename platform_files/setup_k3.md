curl -sfL https://get.k3s.io | \
  INSTALL_K3S_VERSION="v1.33.6+k3s1" \
  INSTALL_K3S_EXEC="server --node-ip=10.0.0.2 --advertise-address=10.0.0.2 --flannel-iface=enp7s0" \
  sh -


and 

/usr/local/bin/k3s-uninstall.sh


get join token from k3s serveer, store in terraform cloud

cat /var/lib/rancher/k3s/server/node-token

install doppler, prepare_env to store docker hub pull secret in default namespace via doppler
