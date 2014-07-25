package org.rhq.enterprise.server.storage.maintenance;

import java.util.Map;

import org.rhq.server.storage.maintenance.ServerOperation;

/**
 * @author John Sanda
 */
public class UpdateSchema {

    @ServerOperation(name = "updateSchema")
    public void updateSchemaIfNecessary(Map<String, Object> args) {
        // TODO add logic for updating replication_factor
    }

}
