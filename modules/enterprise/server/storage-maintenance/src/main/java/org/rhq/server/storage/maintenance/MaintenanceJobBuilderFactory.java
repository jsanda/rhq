package org.rhq.server.storage.maintenance;

/**
 * @author John Sanda
 */
public class MaintenanceJobBuilderFactory {

    public MaintenanceJobBuilderFactory() {

    }

    MaintenanceJobBuilder newBuilder(int type) {
        // We should try to keep the factory and builders loosely coupled so that adding,
        // changing, or removing a builder does not require changing the factory. One way
        // that we could do that is with a simple mapping file that maps job codes (which
        // could be strings or integers) to the builder class names. This could be a simple
        // properties file.
        //
        // Another approach could be to have the factory scan the
        // classpath for builder classes. We could use an annotation on the builders to
        // specify the job type. I think this strategy is more involved than a mapping file
        // but offers some advantages in terms of code maintenance.

        return new DeployBuilder();
    }

}
