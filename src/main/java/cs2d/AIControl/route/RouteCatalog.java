package cs2d.AIControl.route;

import java.util.List;
import java.util.Optional;

/** Read-only catalog of every authored route for one map. */
public interface RouteCatalog {

    String mapName();

    List<RouteDescriptor> routes();

    List<RouteDescriptor> routesFor(RouteType type);

    Optional<RouteDescriptor> find(String routeId);

    default boolean isEmpty() {
        return routes().isEmpty();
    }
}
