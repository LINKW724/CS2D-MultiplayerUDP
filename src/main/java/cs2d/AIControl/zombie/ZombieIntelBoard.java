package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Player;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import java.util.*;

/** Instance-owned and faction-scoped; no live mutable Player/position references are retained. */
public final class ZombieIntelBoard {
    public static final long MEMORY_MS = 3_000L;
    private final Map<Player.Team, Map<String, Observation>> observations = new EnumMap<>(Player.Team.class);

    public synchronized void report(Unit observer, Unit enemy, long now) {
        if (observer == null || enemy == null || observer.health() <= 0 || enemy.health() <= 0
                || observer.id().equals(enemy.id()) || !ZombieHostilityPolicy.hostile(observer.team(), enemy.team()))
            return;
        Map<String, Observation> team = observations.computeIfAbsent(observer.team(), ignored -> new HashMap<>());
        Observation previous = team.get(enemy.id());
        if (previous == null || previous.contact().observedAt() <= now) {
            team.put(enemy.id(), new Observation(observer.id(),
                    new Contact(enemy.id(), enemy.position(), enemy.health(), now)));
        }
    }

    public synchronized List<Contact> contacts(Player.Team team, Map<String, Unit> current, long now) {
        Map<String, Observation> entries = observations.getOrDefault(team, new HashMap<>());
        entries.values().removeIf(observation -> {
            Unit observer = current.get(observation.observerId());
            Unit target = current.get(observation.contact().id());
            long age = now - observation.contact().observedAt();
            return age < 0 || age > MEMORY_MS || observer == null || observer.health() <= 0
                    || observer.team() != team || target == null || target.health() <= 0
                    || !ZombieHostilityPolicy.hostile(team, target.team());
        });
        return entries.values().stream().map(Observation::contact)
                .sorted(Comparator.comparing(Contact::id)).toList();
    }

    public synchronized void clear() { observations.clear(); }
    private record Observation(String observerId, Contact contact) {}
}
