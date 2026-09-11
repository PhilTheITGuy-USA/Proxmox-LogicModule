/*
 * BatchScript collection: Proxmox subscription status and expiry, per online node.
 *
 * Node rows in /cluster/resources already carry a "level" field, which Proxmox_VE_Nodes
 * reports as Subscribed at no extra cost. This module exists for the part that field
 * cannot answer: when the subscription runs out.
 *
 * nextduedate is a "YYYY-MM-DD" string, not an epoch, so it is parsed rather than
 * subtracted. A node with no subscription has no due date at all, and reports none --
 * zero days remaining would read as "expires today" on every unlicensed node in the
 * estate.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

def SECONDS_PER_DAY = 86400.0d

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def now = System.currentTimeMillis() / 1000L

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // Per-node try: one unreachable node must not cost the others their data.
        try {
            def subscription = pveGet('/nodes/' + nodeName + '/subscription') ?: [:]
            def id = node.id
            def status = subscription.status?.toString()?.toLowerCase() ?: ''

            pveEmit(id, 'Active', status == 'active' ? 1 : 0)
            pveEmit(id, 'Sockets', subscription.sockets ?: 0)

            /*
             * checktime is when Proxmox last validated the key against the subscription
             * server. A key that has not been checked in a long time will eventually stop
             * being honoured, so the age is worth watching independently of status.
             */
            if (subscription.checktime != null) {
                pveEmit(id, 'DaysSinceLastCheck',
                        pveRound(((now as double) - ((subscription.checktime as double))) /
                                 SECONDS_PER_DAY))
            }

            def dueDate = subscription.nextduedate?.toString()
            if (dueDate && dueDate ==~ /^\d{4}-\d{2}-\d{2}$/) {
                // Parsed as UTC midnight; a day either way does not matter at this scale.
                def due = java.time.LocalDate.parse(dueDate)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
                pveEmit(id, 'DaysUntilDue', pveRound(((due as double) - (now as double)) /
                                                    SECONDS_PER_DAY))
            }
        } catch (Exception nodeException) {
            System.err.println('Subscription collection skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox subscription collection failed: ' + exception.message)
    return 2
}
