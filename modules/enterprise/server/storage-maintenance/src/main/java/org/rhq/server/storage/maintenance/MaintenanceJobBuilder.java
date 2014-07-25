package org.rhq.server.storage.maintenance;

import java.util.Collection;

import org.rhq.core.domain.cloud.StorageNode;
import org.rhq.core.domain.storage.MaintenanceJob;

/**
 * @author John Sanda
 */
public interface MaintenanceJobBuilder {

    MaintenanceJob build(StorageNode storageNode, Collection<StorageNode> cluster);

}
