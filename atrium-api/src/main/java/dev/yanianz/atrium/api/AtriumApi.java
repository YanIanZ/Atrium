package dev.yanianz.atrium.api;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.user.UserService;
import java.util.Set;

public interface AtriumApi {
    UserService users();

    /** Services published by enabled modules, e.g. {@code services().get(EconomyService.class)}. */
    ServiceRegistry services();

    /** Ids of the modules that are currently enabled. */
    Set<String> enabledModules();
}
