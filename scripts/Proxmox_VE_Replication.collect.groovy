/*
 * BatchScript collection: storage replication job health.
 *
 * The datapoint that matters is how stale the replica is, not whether the last run
 * happened to fail: a job that has been failing for a day is a job whose replica is a day
 * old, and SecondsSinceLastSync says that directly.
 *
 * last_sync, last_try, duration and fail_count are only present once a job has actually
 * run. A job that has never run reports no sync age rather than an age of zero, which
 * would read as "replicated just now" -- the opposite of the truth.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def now = System.currentTimeMillis() / 1000L
    def seen = [] as Set

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // Replication is optional; its absence is not a collection failure.
        try {
            def jobs = pveGet('/nodes/' + nodeName + '/replication') ?: []

            jobs.each { job ->
                def id = job.id?.toString()
                if (!id || seen.contains(id)) { return }
                seen << id

                pveEmit(id, 'FailCount', job.fail_count ?: 0)
                pveEmit(id, 'Failed', job.error ? 1 : 0)
                pveEmit(id, 'Disabled', job.disable ? 1 : 0)
                // pid is only populated while the job is actually running.
                pveEmit(id, 'Running', job.pid ? 1 : 0)

                if (job.last_sync != null) {
                    pveEmit(id, 'SecondsSinceLastSync',
                            pveRound((now as double) - ((job.last_sync as double))))
                }
                if (job.duration != null) {
                    pveEmit(id, 'DurationSeconds', pveRound((job.duration as double)))
                }

                if (job.error) {
                    System.err.println('Replication job ' + id + ' failed: ' + job.error)
                }
            }
        } catch (Exception nodeException) {
            System.err.println('Replication collection skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox replication collection failed: ' + exception.message)
    return 2
}
