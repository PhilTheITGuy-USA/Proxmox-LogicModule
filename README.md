# LogicMonitor Proxmox VE LogicModule bundle

This bundle monitors Proxmox VE through its HTTPS JSON API. It is designed for a LogicMonitor Collector with Groovy scripting enabled and covers cluster/node health, QEMU VMs, LXC containers, and storage. API monitoring is required for virtualization-level visibility; SNMP can be layered on for the underlying Linux hardware.

## Install

1. Create a least-privilege Proxmox API token (suggested role: `PVEAuditor`) and set these resource properties on the Proxmox resource:
   - `pve.api.url` = `https://pve.example.com:8006`
   - `pve.api.token` = `monitor@pam!logicmonitor=TOKEN_SECRET`
   - optional `pve.api.timeout` = milliseconds, default `10000`
   - optional `pve.api.insecure` = `true` only for lab/self-signed certificates
2. Set `pve.monitor = true` on each Proxmox resource. Apply the DataSources to `getPropValue("pve.monitor") && pve.monitor == "true"`.
3. Create the four DataSources described in `logicmodule.json`.
4. Use the corresponding Embedded Groovy scripts and arguments:
   - Cluster: `scripts/Proxmox_VE_Cluster_CT.groovy`, collection argument `cluster`.
   - Node: `scripts/Proxmox_VE_Node_AD.groovy` / `node discover` for AD; `scripts/Proxmox_VE_Node_CT.groovy` / `node` for collection.
   - Guest: `scripts/Proxmox_VE_Guest_AD.groovy` / `guest discover` for AD; `scripts/Proxmox_VE_Guest_CT.groovy` / `guest` for collection.
   - Storage: `scripts/Proxmox_VE_Storage_AD.groovy` / `storage discover` for AD; `scripts/Proxmox_VE_Storage_CT.groovy` / `storage` for collection.
5. Configure datapoints with Raw Metric `output`, Post Processor `namevalue(datapointKey)`, and Metric Type `Gauge`.

The `*_AD.groovy` scripts are for Active Discovery and the `*_CT.groovy` scripts are for collection. For a standalone Proxmox host, disable or omit the Cluster DataSource because `/cluster/status` has no meaningful cluster metrics outside a cluster.

The JSON is a portable design manifest, not a raw LogicMonitor account export (account exports contain tenant-specific IDs and thresholds). Import the definitions through My Module Toolbox, then copy the datapoints, scripts, arguments, and thresholds from the manifest.

## Coverage

Node: availability, CPU utilization, load, memory, swap, root filesystem, uptime, and network traffic. Guests: power state, CPU, memory, disk, network, uptime, and configured limits for both QEMU and LXC. Storage: availability, used/free bytes, utilization, and content type. Cluster: quorum, votes, and expected votes.

The script exits non-zero on API failure so a transient outage does not delete discovered instances.
