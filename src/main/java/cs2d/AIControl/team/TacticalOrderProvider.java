package cs2d.AIControl.team;

import java.util.Optional;

/** Read-only order boundary consumed by individual AI controllers. */
@FunctionalInterface
public interface TacticalOrderProvider {
    Optional<TacticalOrder> orderFor(String agentId);
}
