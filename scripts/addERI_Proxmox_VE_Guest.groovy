/*
 * ERI PropertySource: label a Proxmox guest that is monitored as its own resource as a
 * virtual machine rather than a physical server.
 *
 * Why guests read PhysicalServer. LogicMonitor's built-in addERI_Windows and addERI_Linux
 * default to PhysicalServer and upgrade a resource to VirtualMachineResource only when one
 * of its MACs carries an OUI on lm.data.topo's VM list, or the model or dmesg names a known
 * hypervisor. Proxmox's default guest prefix, bc:24:11, is on no list, and a Proxmox guest
 * reports a generic QEMU model. The MAC prefix is the one thing a Windows VM over WMI, a
 * Linux VM over SNMP and an LXC container over SNMP all have in common.
 *
 * Why only Linux and Windows. The AppliesTo ends in && (isLinux() || isWindows()), so a
 * virtual appliance running as a guest -- FortiGate-VM, pfSense/OPNsense, a virtual router
 * -- keeps the type its own ERI modules give it rather than a priority-3 VM label keyed on
 * nothing but its MAC prefix.
 *
 * Why priority 3. The resource type comes from the highest-priority ERI entry ACROSS
 * categories, and the built-in net.l2 entry is priority 2: at priority 1 this key merges
 * but the type stays PhysicalServer. Keys from every category still merge into the ID,
 * so this key adds to the guest's MAC-based ERIs rather than replacing them. The key joins
 * nothing -- the guest already meets its map vertex through the MAC net.l2 holds -- it
 * exists to carry the type at a priority that wins.
 *
 * Why it never reads pveConfigError. A guest carries no API token, so the preamble always
 * sets pveConfigError here; returning on it would make this module a no-op on every
 * resource it applies to. It makes no API call at all.
 *
 * The match itself is pveGuestMacMatch in the preamble, so the harness can test it: this
 * script, like addERI_Proxmox_VE, imports Collector-only classes and cannot run there.
 *
 * Silent on no match and on any failure: the AppliesTo is deliberately broad where
 * pve.guest.mac_prefix is set on a group, and a PropertySource that prints on a resource
 * it does not understand corrupts that resource's properties.
 */
import org.json.JSONArray
import com.santaba.agent.groovy.utils.GroovyScriptHelper as GSH
import com.logicmonitor.mod.Snippets

try {
    def mac = pveGuestMacMatch(pveHostProp('auto.network.mac_address'),
        pveHostProp('pve.guest.mac_prefix'))
    if (!mac) {
        return 0
    }

    modLoader = GSH.getInstance(GroovySystem.version)
        .getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
    lmtopo = modLoader.load('lm.topo', '0')

    def keyNamespace = pveHostProp('topo.namespace') ? pveHostProp(pveHostProp('topo.namespace')) : ''
    def keyBlacklist = (pveHostProp('topo.blacklist') ?: '').tokenize(',')

    def keys = []
    keys << 'proxmoxve--vm--' + mac

    def eriArray = new JSONArray()
    lmtopo.emitEri('virt.proxmoxve', 3, keys, 'VirtualMachineResource', eriArray)
    lmtopo.printEriArray(eriArray, keyNamespace, keyBlacklist)
    return 0
} catch (Exception exception) {
    // Silent on purpose. See the header.
    return 0
}
