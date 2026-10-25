/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide.
 * Used by Proxmox_VE_GuestPerformance and Proxmox_VE_Snapshots. Proxmox_VE_GuestStatus has its own discovery
 * (Proxmox_VE_GuestStatus.ad) and shares only the preamble helpers, pveGuestList and
 * pveGuestDiscover, so both modules still discover the same instances.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    pveGuestList().each { guest -> pveGuestDiscover(guest) }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest discovery failed: ' + exception.message)
    return 2
}
