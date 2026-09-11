/*
 * Active Discovery: storage replication jobs.
 *
 * Job status is exposed per node at /nodes/{node}/replication, which lists the jobs whose
 * source is that node. The job id ("100-0", guest and job number) is cluster-unique, so it
 * is used as the wildvalue on its own -- deliberately without the node name. A replication
 * job follows its guest, and an id built from the current node would be destroyed by a
 * migration, exactly as it would for a guest instance.
 *
 * Jobs are deduplicated by id: a job appearing from more than one node must not become two
 * instances.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def seen = [] as Set

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        /*
         * Replication is optional and absent on clusters that do not use it, so a failure
         * here is not a discovery failure -- it is the normal case on most installs.
         */
        try {
            def jobs = pveGet('/nodes/' + nodeName + '/replication') ?: []

            jobs.each { job ->
                def id = job.id?.toString()
                if (!id || seen.contains(id)) { return }
                seen << id

                def guest = job.guest?.toString()
                pveDiscover(
                    id,
                    'Replication ' + id,
                    'Replication of guest ' + guest + ' to ' + (job.target ?: 'unknown'),
                    [
                        pve_job     : id,
                        pve_guest   : guest,
                        pve_target  : job.target,
                        pve_source  : job.source,
                        pve_schedule: job.schedule,
                        pve_type    : job.type
                    ]
                )
            }
        } catch (Exception nodeException) {
            System.err.println('Replication discovery skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox replication discovery failed: ' + exception.message)
    return 2
}
