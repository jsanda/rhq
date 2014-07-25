package org.rhq.server.storage.maintenance;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import org.codehaus.jackson.map.ObjectMapper;

import org.rhq.core.domain.cloud.StorageNode;
import org.rhq.core.domain.storage.MaintenanceJob;
import org.rhq.core.domain.storage.MaintenanceStep;

/**
 * @author John Sanda
 */
class DeployBuilder implements MaintenanceJobBuilder {

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public MaintenanceJob build(StorageNode joiningNode, Collection<StorageNode> cluster) {
        MaintenanceJob job = new MaintenanceJob();
        job.setName("deploy " + joiningNode.getAddress());
        List<MaintenanceStep> steps = Lists.newArrayListWithCapacity(cluster.size() + 1);

        for (StorageNode clusterNode : cluster) {
            steps.add(createAnnounceStep(joiningNode, clusterNode));
        }

        steps.add(createBootstrapStep(joiningNode, cluster));
        steps.add(createUpdateSchemaStep());
        steps.add(createMaintenanceStep(joiningNode, cluster));

        return job;
    }

    private MaintenanceStep createAnnounceStep(StorageNode joiningNode, StorageNode clusterNode) {
        MaintenanceStep step = new MaintenanceStep();
        step.setName("announce");
        // TODO use the actual StorageNode object here since the address can change
        step.setNodeAddress(clusterNode.getAddress());
        step.setType(MaintenanceStep.Type.RESOURCE_OP);
        // The announce operation currently takes the full list of addresses. I wanted to
        // refactor it before JON 3.2.0 to take just the new address, but there was not
        // time. This call to setArgs assumes the change is in place.
        step.setArgs("{'address': '" + joiningNode.getAddress() + "'}");
        step.setOnFailure("CONTINUE");  // can probably change to an enum

        return step;
    }

    private MaintenanceStep createBootstrapStep(StorageNode joiningNode, Collection<StorageNode> cluster) {
        try {
            MaintenanceStep step = new MaintenanceStep();
            step.setType(MaintenanceStep.Type.RESOURCE_OP);
            step.setName("prepareForBootstrap");
            // TODO use the actual StorageNode object here since the address can change
            step.setNodeAddress(joiningNode.getAddress());
            step.setOnFailure("HALT");

            Map<String, Object> args  = new HashMap<String, Object>();

            // TODO Ports need to be fetched from storage cluster settings
            // Note though that we need to get those settings at runtime when the step is
            // executed because it is possible that the could change.
            args.put("cqlPort", "9142");
            args.put("gossipPort", "7100");
            args.put("addresses", toAddresses(joiningNode, cluster));

            step.setArgs(mapper.writeValueAsString(args));

            return step;
        } catch (IOException e) {
            // TODO provide exception handling
            throw new RuntimeException("Failed to create step", e);
        }
    }

    private List<String> toAddresses(StorageNode joiningNode, Collection<StorageNode> cluster) {
        List<String> addresses = Lists.newArrayListWithCapacity(cluster.size() + 1);
        addresses.add(joiningNode.getAddress());

        for (StorageNode clusterNode : cluster) {
            addresses.add(clusterNode.getAddress());
        }

        return addresses;
    }

    private MaintenanceStep createUpdateSchemaStep() {
        MaintenanceStep step = new MaintenanceStep();
        step.setType(MaintenanceStep.Type.SERVER_OP);
        step.setName("updateSchema");
        step.setOnFailure("HALT");

        return step;
    }

    private MaintenanceStep createMaintenanceStep(StorageNode joiningNode, Collection<StorageNode> cluster) {
        try {
            MaintenanceStep step = new MaintenanceStep();
            step.setType(MaintenanceStep.Type.RESOURCE_OP);
            step.setName("addNodeMaintenance");

            Map<String, Object> args = new HashMap<String, Object>();
            args.put("runRepair", true);  // Note that we only want to run repair if we change
            // RF, so we really need the update schema to tell
            // us whether or not it is necessary. We could accomplish
            // this with a shared state between steps.
            args.put("updateSeedsList", true);
            args.put("addresses", toAddresses(joiningNode, cluster));

            step.setArgs(mapper.writeValueAsString(args));

            return step;
        } catch (IOException e) {
            // TODO provide exception handling
            throw new RuntimeException("Failed to create step");
        }
    }

}
