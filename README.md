# LogicMonitor Proxmox VE LogicModule bundle

This bundle monitors Proxmox VE through its HTTPS JSON API. It is designed for a LogicMonitor Collector with Groovy scripting enabled and covers cluster/node health, QEMU VMs, LXC containers, and storage. API monitoring is required for virtualization-level visibility; SNMP can be layered on for the underlying Linux hardware.

## Install

1. Create a least-privilege Proxmox API token (suggested role: `PVEAuditor`) and set these resource properties on the Proxmox resource:
   - `pve.api.url` = `https://pve.example.com:8006`
   - `pve.api.token` = `monitor@pam!logicmonitor=TOKEN_SECRET`
   - optional `pve.api.timeout` = milliseconds, default `10000`
   - optional `pve.api.insecure` = `true` only for lab/self-signed certificates
2. Upload `scripts/LM_Proxmox.groovy` to the DataSources as the Groovy script.
3. Create the four DataSources described in `logicmodule.json`, using the same script and the listed `pve.mode` argument. Configure Active Discovery with the listed mode.
4. Apply the DataSources to resources matching `pve.api.url != ""`.

The JSON is a portable design manifest, not a raw LogicMonitor account export (account exports contain tenant-specific IDs and thresholds). Import the definitions through My Module Toolbox, then copy the datapoints and thresholds from the manifest.

## Coverage

Node: availability, CPU utilization, load, memory, swap, root filesystem, uptime, and network traffic. Guests: power state, CPU, memory, disk, network, uptime, and configured limits for both QEMU and LXC. Storage: availability, used/free bytes, utilization, and content type. Cluster: quorum, votes, and expected votes.

The script exits non-zero on API failure so a transient outage does not delete discovered instances.
