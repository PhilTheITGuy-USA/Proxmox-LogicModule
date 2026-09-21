#!/usr/bin/env python3
"""
Proxmox VE dashboard covering the eight Tier 2 modules.

Layout on LogicMonitor's 12-column grid:

    rows  1-6   Ceph Health | Ceph OSDs and Monitors      | Alert Status (noc)
                Backup Coverage | Ceph Raw Utilisation   |
    rows  7-9   OSD Fill | OSD Commit Latency | OSD Apply Latency
    rows 10-12  Ceph OSDs (full width)
    rows 13-15  Certificates | Subscriptions
    rows 16-18  Replication Jobs | Physical Disks
    rows 19-21  Node Services (full width)
    row  22     Proxmox VE Alerts (full width)

Companion to Proxmox_VE_Tier1, and built on the two conventions that dashboard proved in
a portal: modules addressed as "<displayedAs> (<name>)", series legended on ##INSTANCE##.

Most Tier 2 datapoints are conditional -- every Ceph figure is withheld on a cluster
without Ceph, replica age on a job that has never run, disk wear on a disk with no wear
attribute. A bigNumber tile aimed at an absent datapoint renders blank rather than
erroring, so the Ceph tiles are expected to be empty on a cluster with no Ceph, and
CephAvailable is on the Ceph tile to say why. Table cells for a withheld datapoint are
simply empty.
"""

from dashboards import Dashboard, module_ref

# Addressed as "<displayedAs> (<name>)". build.py checks each of these resolves to a
# real module with the datapoints used below, because nothing in the portal would.
CEPH = module_ref("Proxmox VE Ceph", "Proxmox_VE_Ceph")
CEPH_OSD = module_ref("Proxmox VE Ceph OSD", "Proxmox_VE_CephOSD")
BACKUP = module_ref("Proxmox VE Backup Coverage", "Proxmox_VE_BackupCoverage")
CERTS = module_ref("Proxmox VE Certificates", "Proxmox_VE_Certificates")
SERVICES = module_ref("Proxmox VE Node Services", "Proxmox_VE_NodeServices")
DISKS = module_ref("Proxmox VE Disks", "Proxmox_VE_Disks")
REPLICATION = module_ref("Proxmox VE Replication", "Proxmox_VE_Replication")
SUBSCRIPTION = module_ref("Proxmox VE Subscription", "Proxmox_VE_Subscription")


def build() -> Dashboard:
    d = Dashboard(
        name="Proxmox VE Infrastructure",
        description=(
            "Infrastructure metrics for Proxmox VE: Ceph cluster and per-OSD health, "
            "backup coverage, certificate and subscription expiry, replication jobs, "
            "physical disk SMART and wear, and node service state. Covers the eight "
            "Tier 2 modules of the Proxmox VE LogicModule suite."
        ),
        group="Proxmox VE",
        # "*" matches whatever resource carries the API token. Scope it to a resource
        # group in the dashboard's token settings if you monitor more than one cluster.
        resource_group="*",
    )

    # ------------------------------------------------------ rows 1-6: overview

    # CephAvailable is the one Ceph datapoint emitted on every run. With it on the
    # tile, a cluster without Ceph shows a 0 beside the blanks instead of an
    # unexplained empty widget.
    d.numbers(1, 1, 4, 3, "Ceph Health", [
        (CEPH, "CephAvailable", "Ceph Present (1=yes)", 0),
        (CEPH, "HealthOK", "HEALTH_OK (1=yes)", 0),
        (CEPH, "HealthChecks", "Active Health Checks", 0),
        (CEPH, "PGsNotClean", "PGs Not Clean", 0),
    ], description="Blank on a cluster without Ceph: every Ceph datapoint except "
                   "CephAvailable is withheld there. PGs Not Clean is non-zero during "
                   "normal recovery and rebalancing.")

    d.numbers(1, 5, 4, 3, "Ceph OSDs and Monitors", [
        (CEPH, "OSDsUp", "OSDs Up", 0),
        (CEPH, "OSDsDown", "OSDs Down", 0),
        (CEPH, "OSDsOut", "OSDs Out", 0),
        (CEPH, "MonitorsInQuorum", "Monitors in Quorum", 0),
    ], description="Cluster-wide counts from ceph status. OSDs Out is expected during "
                   "a planned rebalance.")

    d.alert_status(1, 9, 4, 6, "Proxmox VE Alert Status", "Proxmox VE*")

    d.numbers(4, 1, 4, 3, "Backup Coverage", [
        (BACKUP, "GuestsNotBackedUp", "Guests Not Backed Up", 0),
        (BACKUP, "VMsNotBackedUp", "VMs Not Backed Up", 0),
        (BACKUP, "ContainersNotBackedUp", "Containers Not Backed Up", 0),
    ], description="Guests covered by no backup job. Coverage only: whether the jobs "
                   "succeed is not something this module can see.")

    d.graph(4, 5, 4, 3, "Ceph Raw Utilisation",
            [d.series(CEPH, "UsedPercent", display="area")], "%",
            timescale="7days",
            description="Raw capacity across all OSDs, before replication. Ceph "
                        "degrades well before it is full.")

    # ------------------------------------------------------- rows 7-12: OSDs

    d.graph(7, 1, 4, 3, "OSD Fill",
            [d.series(CEPH_OSD, "UsedPercent")], "%", top_x=20,
            description="An uneven spread across OSDs points at CRUSH weighting.")
    d.graph(7, 5, 4, 3, "OSD Commit Latency",
            [d.series(CEPH_OSD, "CommitLatencyMs")], "ms", maximum="NaN", top_x=20)
    d.graph(7, 9, 4, 3, "OSD Apply Latency",
            [d.series(CEPH_OSD, "ApplyLatencyMs")], "ms", maximum="NaN", top_x=20)

    d.table(10, 1, 12, 3, "Ceph OSDs", CEPH_OSD, [
        d.column("Up", "Up", display_type="number", maximum=1, rounding=0),
        d.column("In", "In", display_type="number", maximum=1, rounding=0),
        d.column("UsedPercent", "Used %", warn=75, error=85),
        d.column("PlacementGroups", "PGs", display_type="number", maximum="NaN",
                 rounding=0),
        d.column("CommitLatencyMs", "Commit ms", display_type="number",
                 maximum="NaN", rounding=0),
        d.column("ApplyLatencyMs", "Apply ms", display_type="number",
                 maximum="NaN", rounding=0),
        d.column("Reweight", "Reweight", display_type="number", maximum=1),
    ], top_x=50,
       description="Reweight below 1.0 means the OSD has been down-weighted. A large "
                   "PG imbalance across OSDs indicates a CRUSH or weighting problem.")

    # ------------------------------------------- rows 13-15: expiry and licence

    # Days-remaining columns carry no colour threshold: the table helper only
    # expresses ">=", and for these a low value is the bad one. The module
    # thresholds still alert.
    d.table(13, 1, 6, 3, "Certificates", CERTS, [
        d.column("Expired", "Expired", display_type="number", maximum=1, warn=1,
                 rounding=0),
        d.column("DaysUntilExpiry", "Days Left", display_type="number",
                 maximum="NaN", rounding=0),
        d.column("PublicKeyBits", "Key Bits", display_type="number",
                 maximum="NaN", rounding=0),
    ], description="One row per certificate per node: the cluster CA, pve-ssl and "
                   "pveproxy-ssl expire independently.")

    d.table(13, 7, 6, 3, "Subscriptions", SUBSCRIPTION, [
        d.column("Active", "Active", display_type="number", maximum=1, rounding=0),
        d.column("DaysUntilDue", "Days to Renewal", display_type="number",
                 maximum="NaN", rounding=0),
        d.column("Sockets", "Sockets", display_type="number", maximum="NaN",
                 rounding=0),
    ], description="Days to Renewal is empty on a node with no subscription.")

    # ------------------------------------------ rows 16-18: replication and disks

    d.table(16, 1, 6, 3, "Replication Jobs", REPLICATION, [
        d.column("Failed", "Failed", display_type="number", maximum=1, warn=1,
                 rounding=0),
        d.column("FailCount", "Fail Count", display_type="number", maximum="NaN",
                 warn=1, error=3, rounding=0),
        d.column("SecondsSinceLastSync", "Replica Age (s)", display_type="number",
                 maximum="NaN", rounding=0),
        d.column("Running", "Running", display_type="number", maximum=1, rounding=0),
        d.column("Disabled", "Disabled", display_type="number", maximum=1,
                 rounding=0),
    ], description="Replica Age is empty for a job that has never run.")

    d.table(16, 7, 6, 3, "Physical Disks", DISKS, [
        d.column("SmartHealthOK", "SMART OK", display_type="number", maximum=1,
                 rounding=0),
        d.column("SmartHealthKnown", "SMART Known", display_type="number",
                 maximum=1, rounding=0),
        d.column("LifeRemainingPercent", "Life Left %"),
        d.column("Mounted", "Mounted", display_type="number", maximum=1, rounding=0),
        d.column("SizeBytes", "Size Bytes", display_type="number", maximum="NaN",
                 rounding=0),
    ], top_x=50,
       description="SMART OK is empty where SMART Known is 0. Life Left is reported "
                   "only for SSDs that expose a wear attribute.")

    # ----------------------------------------------- rows 19-21: node services

    d.table(19, 1, 12, 3, "Node Services", SERVICES, [
        d.column("Failed", "Failed", display_type="number", maximum=1, warn=1,
                 rounding=0),
        d.column("Running", "Running", display_type="number", maximum=1, rounding=0),
        d.column("Enabled", "Enabled", display_type="number", maximum=1, rounding=0),
        d.column("Installed", "Installed", display_type="number", maximum=1,
                 rounding=0),
    ], top_x=100,
       description="Running ships without a threshold: several Proxmox services are "
                   "legitimately stopped on a node that does not use them. Failed is "
                   "the one that means something broke.")

    # --------------------------------------------------------- row 22: alerts

    d.alert_table(22, 1, 12, 4, "Proxmox VE Alerts", "Proxmox_VE*")

    return d
