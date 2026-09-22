/*
 * BatchScript collection: physical disk health and wear, per node.
 *
 * Two fields here are not what they look like, and both come straight from Proxmox's
 * Diskmanage:
 *
 *   health   a string, defaulting to "UNKNOWN" when smartctl could not be read -- not a
 *            failure. Reported as two datapoints so an unreadable disk cannot be mistaken
 *            for a failing one.
 *   wearout  the literal string "N/A" on anything that is not SSD-like, or when the wear
 *            attribute is missing. Emitted only when it is genuinely a number.
 *
 * This endpoint shells out to smartctl per disk, so it is deliberately on a long interval.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

// Values smartctl reports for a disk that is passing. Anything else that is a real
// verdict -- FAILED, and the vendor variants -- reads as not-OK.
def healthyStates = ['PASSED', 'OK']
// Values that mean "no verdict available", as opposed to a bad verdict.
def unknownStates = ['UNKNOWN', 'SMART DISABLED', '']

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // Per-node try: one unreachable node must not cost the others their data.
        try {
            def disks = pveGet('/nodes/' + nodeName + '/disks/list') ?: []

            disks.each { disk ->
                def devpath = disk.devpath?.toString()
                if (!devpath) { return }

                def id = nodeName + '/' + devpath.replaceAll('^/dev/', '')
                def health = (disk.health?.toString() ?: '').toUpperCase()
                def known = !unknownStates.contains(health)

                pveEmit(id, 'SizeBytes', disk.size ?: 0)
                // SizeBytes stays for anything doing arithmetic; SizeGB is what a
                // dashboard can print. See pveGB in the preamble.
                pveEmit(id, 'SizeGB', pveGB(disk.size))
                pveEmit(id, 'SmartHealthKnown', known ? 1 : 0)
                pveEmit(id, 'Mounted', disk.mounted ? 1 : 0)

                /*
                 * The verdict is emitted only when there is one. A disk whose SMART cannot
                 * be read is not a failing disk, and emitting 0 here would alert as though
                 * it were -- the whole reason UNKNOWN is separated from FAILED above.
                 */
                if (known) {
                    pveEmit(id, 'SmartHealthOK', healthyStates.contains(health) ? 1 : 0)
                }

                /*
                 * wearout is a number only on SSD-like disks that report a wear attribute.
                 * Everywhere else it is the string "N/A", which must not become a zero --
                 * zero would read as a disk with no life left.
                 */
                def wearout = disk.wearout
                if (wearout != null && wearout.toString().isNumber()) {
                    pveEmit(id, 'LifeRemainingPercent', pveRound(wearout.toString().toDouble()))
                }
            }
        } catch (Exception nodeException) {
            System.err.println('Disk collection skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox disk collection failed: ' + exception.message)
    return 2
}
