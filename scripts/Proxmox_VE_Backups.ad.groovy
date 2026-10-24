/*
 * Active Discovery: backup jobs and their guests, for Proxmox_VE_Backups.
 *
 * One instance per guest per job, grouped by job: auto.pve_backup_job is the job's comment,
 * or its id when it has none. A guest in two jobs has an instance under each. Guests that
 * no job covers are instances too, in the group "No backup job", so each one can be seen
 * and alerted on by name. Each job instance lists the guest's volumes the job backs up and
 * the ones it leaves out, with the reason, as properties. pveBackupInstances in the preamble builds the list, the same one
 * collection uses.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    pveBackupInstances().each { instance ->
        def guest = (instance.name ?: (instance.type + '-' + instance.vmid)) + ' (' + instance.vmid + ')'
        pveDiscover(
            instance.id,
            guest,
            instance.covered ? 'Backed up by ' + instance.job : 'Covered by no backup job',
            [
                pve_backup_job     : instance.job,
                pve_backup_job_id  : instance.jobId,
                pve_backup_schedule: instance.schedule,
                pve_backup_storage : instance.storage,
                pve_vmid           : instance.vmid,
                pve_type           : instance.type,
                pve_backup_included_volumes: instance.included?.join(', '),
                pve_backup_excluded_volumes: instance.excluded?.join(', ')
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox backup discovery failed: ' + exception.message)
    return 2
}
