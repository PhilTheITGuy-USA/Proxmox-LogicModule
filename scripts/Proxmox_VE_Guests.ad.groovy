/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide.
 * Shared by Proxmox_VE_GuestPerformance and Proxmox_VE_GuestStatus.
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
