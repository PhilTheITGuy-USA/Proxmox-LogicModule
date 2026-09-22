#!/usr/bin/env python3
"""
Proxmox VE dashboard covering the six Tier 1 modules.

Layout on LogicMonitor's 12-column grid:

    rows  1-6   Cluster Health | Capacity & Overcommit        | Alert Status (noc)
                Nodes by Utilisation | Storage by Utilisation |
    rows  7-12  Node CPU | Node Memory | Node Load Per Core
                Node IO Wait | Node Root FS | Node Swap
    rows 13-18  Top Guests by CPU | by Memory | Top Guests table
                Guest Network Throughput | Guest Disk Throughput
    rows 19-21  Storage Used Percent | Storage Capacity table
    row  22     Guest Status (full width)
    row  25     Proxmox VE Alerts (full width)

Structure mirrors LogicMonitor's own VMware and Hyper-V dashboards: capacity,
performance and status are separate tiles rather than one fat one, for the same reason
the modules are separate.
"""

from dashboards import Dashboard, module_ref

# Addressed as "<displayedAs> (<name>)". build.py checks each of these resolves to a
# real module with the datapoints used below, because nothing in the portal would.
CLUSTER = module_ref("Proxmox VE Cluster", "Proxmox_VE_Cluster")
NODES = module_ref("Proxmox VE Nodes", "Proxmox_VE_Nodes")
NODE_DETAIL = module_ref("Proxmox VE Node Detail", "Proxmox_VE_NodeDetail")
GUEST_PERF = module_ref("Proxmox VE Guest Performance", "Proxmox_VE_GuestPerformance")
GUEST_STATUS = module_ref("Proxmox VE Guest Status", "Proxmox_VE_GuestStatus")
STORAGE = module_ref("Proxmox VE Storage Capacity", "Proxmox_VE_StorageCapacity")


def build() -> Dashboard:
    d = Dashboard(
        name="Proxmox VE",
        description=(
            "Core metrics for Proxmox VE: cluster health and capacity, per-node "
            "utilisation and load, guest CPU/memory/network/disk, storage capacity and "
            "guest state. Covers the six Tier 1 modules of the Proxmox VE LogicModule "
            "suite."
        ),
        group="Proxmox VE",
        # "*" matches whatever resource carries the API token. Scope it to a resource
        # group in the dashboard's token settings if you monitor more than one cluster.
        resource_group="*",
    )

    # ------------------------------------------------------ rows 1-6: overview

    # ClusterConfigured rather than Quorate on purpose. Quorate is conditional and is
    # withheld on a standalone host, and a tile pointed at an absent datapoint renders
    # blank rather than erroring -- which reads as a broken dashboard, not as "this host
    # is not clustered". ClusterConfigured is emitted on every run.
    d.numbers(1, 1, 5, 3, "Proxmox VE Cluster Health", [
        (CLUSTER, "ClusterConfigured", "Clustered (1=yes)", 0),
        (CLUSTER, "NodesOnline", "Nodes Online", 0),
        (CLUSTER, "NodesOffline", "Nodes Offline", 0),
        (CLUSTER, "GuestsRunning", "Guests Running", 0),
    ], description="Cluster-wide rollup. Quorum and HA datapoints are withheld on a "
                   "standalone host, so they are not shown here; see the alert widgets.")

    d.numbers(1, 6, 4, 3, "Proxmox VE Capacity and Overcommit", [
        (CLUSTER, "GuestsTotal", "Guests Total", 0),
        (CLUSTER, "VCPUsAllocated", "vCPUs Allocated", 0),
        (CLUSTER, "VCPUOvercommitRatio", "vCPU Overcommit", 2),
        (CLUSTER, "MemoryOvercommitRatio", "Memory Overcommit", 2),
    ], description="Overcommit ratios ship without thresholds; a defensible number is "
                   "site-specific.")

    d.alert_status(1, 10, 3, 6, "Proxmox VE Alert Status", "Proxmox VE*")

    d.table(4, 1, 5, 3, "Nodes by Utilisation", NODES, [
        d.column("CPUUsagePercent", "CPU %", warn=85, error=92),
        d.column("MemoryUsagePercent", "Memory %", warn=85, error=92),
        d.column("Status", "Up", display_type="raw", rounding=0),
    ])

    d.table(4, 6, 4, 3, "Storage by Utilisation", STORAGE, [
        d.column("UsedPercent", "Used %", warn=80, error=90),
        d.column("FreeGB", "Free GB", display_type="raw", rounding=0),
    ])

    # --------------------------------------------------- rows 7-12: node detail

    d.graph(7, 1, 4, 3, "Node CPU Utilisation",
            [d.series(NODES, "CPUUsagePercent", display="area")], "%")
    d.graph(7, 5, 4, 3, "Node Memory Utilisation",
            [d.series(NODES, "MemoryUsagePercent", display="area")], "%")
    d.graph(7, 9, 4, 3, "Node Load Per Core",
            [d.series(NODE_DETAIL, "LoadPerCore")], "#", maximum="NaN",
            description="Load average normalised by core count, which is the form that "
                        "compares across differently sized nodes.")

    d.graph(10, 1, 4, 3, "Node IO Wait",
            [d.series(NODE_DETAIL, "IOWaitPercent", display="area")], "%")
    d.graph(10, 5, 4, 3, "Node Root Filesystem Used",
            [d.series(NODE_DETAIL, "RootFSUsedPercent", display="area")], "%")
    d.graph(10, 9, 4, 3, "Node Swap Used",
            [d.series(NODE_DETAIL, "SwapUsagePercent", display="area")], "%")

    # ------------------------------------------------------- rows 13-18: guests

    d.graph(13, 1, 4, 3, "Top Guests by CPU Utilisation",
            [d.series(GUEST_PERF, "CPUUsagePercent")], "%")
    d.graph(13, 5, 4, 3, "Top Guests by Memory Utilisation",
            [d.series(GUEST_PERF, "MemoryUsagePercent")], "%")

    d.table(13, 9, 4, 3, "Top Guests by CPU", GUEST_PERF, [
        d.column("CPUUsagePercent", "CPU %", warn=90, error=95),
        d.column("MemoryUsagePercent", "Memory %", warn=90, error=95),
    ])

    d.graph(16, 1, 6, 3, "Guest Network Throughput", [
        d.series(GUEST_PERF, "DataRateRxMB", legend="##INSTANCE## rx"),
        d.series(GUEST_PERF, "DataRateTxMB", legend="##INSTANCE## tx"),
    ], "MB/sec", maximum="NaN",
       description="netin/netout are cumulative counters in Proxmox and are collected "
                   "as derive, so this is a rate rather than a climbing total.")

    d.graph(16, 7, 6, 3, "Guest Disk Throughput", [
        d.series(GUEST_PERF, "DiskReadRateMB", legend="##INSTANCE## read"),
        d.series(GUEST_PERF, "DiskWriteRateMB", legend="##INSTANCE## write"),
    ], "MB/sec", maximum="NaN",
       description="Byte counters only. Proxmox exposes no per-guest IOPS or storage "
                   "latency, so there is no equivalent of the Nutanix latency graphs.")

    # ------------------------------------------------------ rows 19-21: storage

    d.graph(19, 1, 6, 3, "Storage Used Percent",
            [d.series(STORAGE, "UsedPercent", display="area")], "%",
            timescale="2days")

    d.table(19, 7, 6, 3, "Storage Capacity", STORAGE, [
        d.column("UsedPercent", "Used %", warn=80, error=90),
        d.column("UsedGB", "Used GB", display_type="raw", rounding=0),
        d.column("CapacityGB", "Capacity GB", display_type="raw", rounding=0),
    ])

    # ------------------------------------------------- row 22: guest inventory

    # Power state, lock and HA health are inventory facts rather than trends, so a
    # full-width table reads better than a graph.
    d.table(22, 1, 12, 3, "Guest Status", GUEST_STATUS, [
        d.column("Status", "Running", display_type="raw", rounding=0),
        d.column("HAManaged", "HA Managed", display_type="raw", rounding=0),
        d.column("HAError", "HA Error", display_type="raw", warn=1, rounding=0),
        d.column("Locked", "Locked", display_type="raw", rounding=0),
        d.column("UpTimeSeconds", "Uptime (s)", display_type="raw", rounding=0),
    ], top_x=-1,
       description="Status ships without a threshold: a stopped guest is usually "
                   "stopped on purpose. HAError only fires for guests the cluster "
                   "itself considers broken.")

    # --------------------------------------------------------- row 25: alerts

    d.alert_table(25, 1, 12, 4, "Proxmox VE Alerts", "Proxmox_VE*")

    return d
