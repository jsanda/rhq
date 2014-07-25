package org.rhq.server.storage.maintenance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>
 * Maintenance jobs consists of resource operations executed by the agent and operations
 * executed by the server. Some examples of server operations include changing the
 * replication_factor and updating the StorageNode address. Some operations might involve
 * only a one or two method calls. Defining a server operation through an interface could
 * lead to a proliferation of a lot of small classes. Defining it as method annotation
 * provides more flexibility on how to organize the code. In some cases it might be
 * appropriate to group multiple operations into a single class while in other cases it
 * might make more sense to put a single operation into its own class.
 * </p>
 * <p>
 * Currently the only requirement for the method is that it takes a single argument which
 * should be a Map&lt;String, Object&gt; of the operation's argument. Those arguments are
 * persisted as JSON.
 * </p>
 *
 * @author John Sanda
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD })
public @interface ServerOperation {

    String name();

}
