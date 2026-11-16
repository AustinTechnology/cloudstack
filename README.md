# CloudStack External extension for Hyper-V failover clusters

`hyperv_cluster/hyperv_cluster.py` is a CloudStack External orchestrator extension for Hyper-V hosts in Windows Server
Failover Clusters. It targets the native External work on the AustinTechnology CloudStack fork (branch
`step7-native-sync`): native discovery/refresh/adoption, NIC and CPU/RAM changes, disks as volumes, ISO and Instance
Snapshots. See `docs/DESIGN.md` for the mapping and `docs/EXTERNAL-EXTENSION-CONTRACT.md` for the wire contract.

**Status: running against a nested two-node Hyper-V failover-cluster lab; see `docs/DESIGN.md` "Live results" for what is proven and what is not.**

## Requirements

- Management server: Python 3 with `pywinrm` (`apt install python3-winrm`).
- Every cluster node: WinRM over HTTPS (5986), Hyper-V and FailoverClusters PowerShell modules, and TCP 2179 (VMConnect) from the management servers for the console.
- Existing VMs: give their network adapters static MAC addresses before adoption (a dynamic MAC can change when the VM starts on another node; CloudStack starts pin it).
- One domain account per cluster mapping, member of Hyper-V Administrators on each node and with full control of the
  cluster (`Grant-ClusterAccess -Full`), and write access to the CSV folders it uses.

## Configuration

Extension (or cluster mapping) details:

| Detail | Meaning |
|---|---|
| `username`, `secret` | Domain account and its password (cluster mapping). Not `password`: CloudStack hides that key and never passes it to the extension |
| `winrm_transport` | `ntlm` (default) or `kerberos` |
| `verify_tls_certificate`, `ca_file` | WinRM TLS checking (default verify) |
| `dns_suffix` or `node_addresses` | How to reach a node by name (`{"hv01":"10.0.0.11"}`) |
| `smb_storages` | `{"name":"\\\\server\\share"}` for SMB storages besides the CSVs |
| `iso_storage` | Storage holding the ISO library (`<storage>\\iso`) |
| `default_storage` | Storage for VMs created from templates when none is given |
| `hyperv_shared_guardian` | `true` once vTPM key protectors open on every node; needed for Secure Boot + TPM |
| Capabilities | `instancediscoversupported`, `instancegetconfigsupported`, `instancescalesupported`, `instancenicchangesupported`, `instancenichotplugsupported`, `instancestoragesupported`, `instanceisosupported`, `instancesnapshotsupported` = `true`. Not `instancevolumesnapshotsupported`. |

Host details (one CloudStack host per node): `url` (node address), `node` (node name, lowercase),
`proxmox_cluster` (failover cluster name, lowercase: CloudStack's key name), `network_bridge` (virtual switch).

Templates: `template_volume` = `<storage>:<path/to/disk.vhdx>`, optional `generation` (1|2), `secure_boot`, `is_full_clone`.

## Console

`console_helper/` serves a VM's VMConnect console (RDP, node port 2179) as VNC for CloudStack's console proxy. Install it
on every management server (`sudo console_helper/install.sh <address the console proxy reaches>`), open TCP 5900-5999 on
that address to the console proxy VMs only, and TCP 2179 from the management servers to every node. The cluster
mapping's account needs VMConnect access (Hyper-V Administrators, or `Grant-VMConnectAccess`). Extension detail
`console_helper_socket` overrides the default `/run/hyperv-console/helper.sock`.

## Tests

```sh
cd hyperv_cluster
python3 -m unittest test_hyperv_cluster      # 53 tests, simulated cluster
python3 check_powershell_syntax.py           # needs pwsh
cd ../console_helper && python3 -m unittest test_hyperv_console_helper   # 6 tests
```
