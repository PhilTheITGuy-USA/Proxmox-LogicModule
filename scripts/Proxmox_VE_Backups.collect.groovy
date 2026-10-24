/*
 * BatchScript collection: each guest's last backup and failed backups, per backup job.
 *
 * Everything comes from vzdump task logs, which PVEAuditor can read. Backup files on a
 * storage are not an option for a read-only token: Proxmox lists a backup file only to a
 * token holding Datastore.AllocateSpace and VM.Backup, and leaves the others out of the
 * listing without an error.
 *
 * A vzdump task covers a whole run, not one guest (its id is empty), so a guest's result is
 * named only in the run's log:
 *
 *   INFO: starting new backup job: vzdump 101 102 --node pve1 ... --storage <id>
 *   INFO: Starting Backup of VM 102 (lxc)
 *   INFO: archive file size: 3.51GB
 *   INFO: Finished Backup of VM 102 (00:00:50)
 *   ERROR: Backup of VM 101 failed - CT is locked (backup)
 *
 * "VM" is written for containers too. The archive size is in binary units (GB here means
 * GiB) and is absent for some storage types, such as Proxmox Backup Server. The run's
 * storage ties each result to the job instances writing to it.
 *
 * Runs are read newest first, across all online nodes, and reading stops once every job
 * guest has a successful backup -- normally after the newest run. A log is read whole: the
 * API returns 50 lines unless asked for more, and a run of several guests is longer. At most
 * the newest MAX_RUNS runs are read in one collection, so a guest whose last good backup is
 * older than that reads as having none.
 *
 * LastBackupAgeHours and LastBackupSizeGB come from the guest's newest successful run;
 * FailedSinceLastBackup is 1 when a newer run to the same storage names the guest as
 * failed. Times come from the task, not the log text. If any online node's task list or
 * any needed run's log cannot be read, those three are withheld for every guest: the
 * missing run may hold a guest's newest result, and a guess would be worse than a gap.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

final int MAX_RUNS = 50

try {
    def instances = pveBackupInstances()
    def now = System.currentTimeMillis() / 1000L
    def online = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }
        .collect { it.node?.toString() }
        .findAll { it }

    // Every finished vzdump run on every online node, newest first.
    def runs = []
    def complete = true
    online.each { nodeName ->
        // Per-node try: one unreachable node must not cost the others their data.
        try {
            (pveGet('/nodes/' + nodeName + '/tasks?typefilter=vzdump&limit=' + MAX_RUNS) ?: []).each { task ->
                if (task.upid && task.starttime != null) {
                    runs << [node: nodeName, upid: task.upid.toString(), start: task.starttime as long]
                }
            }
        } catch (Exception nodeException) {
            complete = false
            System.err.println('Backup runs not read from node ' + nodeName + ': ' + nodeException.message)
        }
    }
    runs = runs.sort { -it.start }.take(MAX_RUNS)

    def startingLine = ~/^INFO: Starting Backup of VM (\d+)/
    def finishedLine = ~/^INFO: Finished Backup of VM (\d+)/
    def failedLine = ~/^ERROR: Backup of VM (\d+) failed/
    def sizeLine = ~/archive file size: ([\d.]+)\s*([KMGT]?)B/
    def storageOption = ~/--storage\s+(\S+)/
    def binary = ['': 1d, K: 1024d, M: 1048576d, G: 1073741824d, T: 1099511627776d]

    // A run without --storage used the node's default; it matches any job, as a job without
    // a storage matches any run.
    def sameStorage = { instance, String storage ->
        instance.storage == null || storage == null || storage == instance.storage
    }
    // Results in the order read, newest run first: the first matching success is the newest.
    def successes = []
    def failures = []
    def covered = instances.findAll { it.covered }
    def newestSuccess = { instance ->
        successes.find { it.vmid == instance.vmid && sameStorage(instance, it.storage) }
    }

    for (run in runs) {
        if (covered.every { newestSuccess(it) }) { break }
        def logPath = '/nodes/' + run.node + '/tasks/' + URLEncoder.encode(run.upid, 'UTF-8') + '/log'
        def lines = []
        try {
            int start = 0
            while (true) {
                def page = pveGet(logPath + '?start=' + start + '&limit=1000') ?: []
                lines.addAll(page.collect { it.t?.toString() ?: '' })
                if (page.size() < 1000) { break }
                start += 1000
            }
        } catch (Exception logException) {
            // An unread run may hold a guest's newest result, so nothing is concluded this time.
            complete = false
            System.err.println('Backup run ' + run.upid + ' not read: ' + logException.message)
            break
        }
        def jobLine = lines.find { it.contains('starting new backup job') }
        def storageMatch = jobLine ? (jobLine =~ storageOption) : null
        def runStorage = storageMatch?.find() ? storageMatch.group(1) : null

        String current = null
        Double currentBytes = null
        lines.each { line ->
            def starting = line =~ startingLine
            def size = line =~ sizeLine
            def finished = line =~ finishedLine
            def failed = line =~ failedLine
            if (starting.find()) {
                current = starting.group(1)
                currentBytes = null
            } else if (size.find()) {
                currentBytes = (size.group(1) as double) * binary[size.group(2)]
            } else if (finished.find()) {
                successes << [storage: runStorage, vmid: finished.group(1), start: run.start,
                              bytes  : finished.group(1) == current ? currentBytes : null]
            } else if (failed.find()) {
                failures << [storage: runStorage, vmid: failed.group(1), start: run.start]
            }
        }
    }

    instances.each { instance ->
        def id = instance.id
        pveEmit(id, 'NotCovered', instance.covered ? 0 : 1)
        if (!instance.covered) { return }

        pveEmit(id, 'JobEnabled', instance.enabled ? 1 : 0)
        pveEmit(id, 'IncludedVolumes', instance.included.size())
        pveEmit(id, 'ExcludedVolumes', instance.excluded.size())
        if (instance.nextRun != null) {
            pveEmit(id, 'HoursUntilNextRun',
                pveRound(((instance.nextRun as double) - (now as double)) / 3600.0d))
        }
        if (!complete) { return }

        def newest = newestSuccess(instance)
        long newestTime = newest ? newest.start as long : 0L
        if (newest) {
            pveEmit(id, 'LastBackupAgeHours',
                pveRound(((now as double) - (newestTime as double)) / 3600.0d))
            if (newest.bytes != null) {
                pveEmit(id, 'LastBackupSizeGB', pveGB(newest.bytes))
            }
        }

        def failed = failures.any { failure ->
            failure.vmid == instance.vmid && sameStorage(instance, failure.storage) && failure.start > newestTime
        }
        pveEmit(id, 'FailedSinceLastBackup', failed ? 1 : 0)
        if (failed) {
            System.err.println('Backup of ' + (instance.name ?: instance.vmid) + ' (' + instance.vmid +
                ')' + (instance.storage ? ' to ' + instance.storage : '') +
                ' has failed since its last good backup.')
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox backup collection failed: ' + exception.message)
    return 2
}
