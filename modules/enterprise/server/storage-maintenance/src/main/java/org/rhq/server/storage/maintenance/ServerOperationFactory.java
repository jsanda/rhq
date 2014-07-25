package org.rhq.server.storage.maintenance;

import java.lang.reflect.Method;

import org.codehaus.jackson.map.ObjectMapper;

import org.rhq.enterprise.server.storage.maintenance.UpdateSchema;

/**
 * This is a factory for producing server operation objects. Because there is no server
 * operation interface, {@link ServerOperationWrapper wrapper} objects are returned instead.
 *
 * @author John Sanda
 */
public class ServerOperationFactory {

    private ObjectMapper mapper = new ObjectMapper();

    public ServerOperationWrapper getServerOperation(String operation) {
        // The check for updateSchema here is hard coded since this is only a PoC. We cannot
        // do this because it introduces a circular dependency at compile time. This can
        // and should be dynamic. There needs to be a mapping of sorts between server
        // operations and the classes that provide them. As long as they are on the
        // classpath of this object, we can instatiate them dynamically.

        if (operation.equals("updateSchema")) {
            UpdateSchema updateSchema = new UpdateSchema();
            return new ServerOperationWrapper(mapper, findOperation(updateSchema.getClass(), operation), updateSchema);
        }

        return null;
    }

    private Method findOperation(Class clazz, String name) {
        for (Method method : clazz.getMethods()) {
            ServerOperation annotation = method.getAnnotation(ServerOperation.class);
            if (annotation != null && annotation.name().equals(name)) {
                return method;
            }
        }
        return null;
    }

}
