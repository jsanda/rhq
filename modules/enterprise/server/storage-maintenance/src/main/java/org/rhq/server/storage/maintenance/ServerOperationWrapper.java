package org.rhq.server.storage.maintenance;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

/**
 * @author John Sanda
 */
class ServerOperationWrapper {

    private ObjectMapper mapper;

    private Method operation;

    private Object target;

    public ServerOperationWrapper(ObjectMapper mapper, Method operation, Object target) {
        this.mapper = mapper;
        this.operation = operation;
        this.target = target;
    }

    /**
     * Executes the server operation
     *
     * @param args The operation arguments which is a JSON string
     */
    public void execute(String args) {
        try {
            TypeReference<HashMap<String,Object>> typeRef = new TypeReference<HashMap<String,Object>>() {};
            HashMap<String, Object> map = mapper.readValue(args, typeRef);
            operation.invoke(target, map);
        } catch (IOException e) {
            // TODO add exception handling
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            // TODO add exception handling
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            // TODO add exception handling
            throw new RuntimeException(e);
        }
    }

}
