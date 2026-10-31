package kr.pokeevents;

import java.util.List;

/**
 * config/poke-events.json: events that start by themselves (server clock).
 *
 * random: every everyMinutes (counted from midnight, so 60 = on the hour), one of the two events at random for
 * minutes; outbreaks pick one of species.
 *
 * schedule: fixed events. days: MON TUE WED THU FRI SAT SUN (empty = every day); time: "HH:mm"; type: "shiny" or
 * "outbreak"; multiplier: shiny chance x N, or the outbreak species' spawn weight x N; minutes: how long;
 * species: outbreak only, one of them is picked at random each time.
 */
public final class EventSchedule {
    public Random random = new Random();
    public List<Entry> schedule = List.of();
    public Legends legends = new Legends();

    /**
     * Rare legendary encounters: every everyMinutes, one roll for the whole server (so how often does not depend on
     * how many play): a box legendary (or mythical) with boxChance, otherwise a sub legendary with subChance, near a
     * random online player. Only one at a time; it leaves after despawnMinutes unless in a battle. level is lowered
     * to the player's RCT level cap when capToLevelCap. Species lists come from Cobblemon's labels; exclude removes some.
     */
    public static final class Legends {
        public boolean enabled = true;
        public int everyMinutes = 5;
        public double subChance = 0.03;
        public double boxChance = 0.005;
        public boolean mythicalAsBox = true;
        public int level = 70;
        public boolean capToLevelCap = true;
        public int despawnMinutes = 10;
        public int minDistance = 16;
        public int maxDistance = 32;
        public List<String> exclude = List.of();
    }

    public static final class Random {
        public boolean enabled = true;
        public int everyMinutes = 60;
        public int minutes = 30;
        public float shinyMultiplier = 3f;
        public float outbreakMultiplier = 30f;
        /** empty: any species with natural spawns except legendaries and mythicals */
        public List<String> species = List.of();
    }

    public static final class Entry {
        public List<String> days = List.of();
        public String time = "20:00";
        public String type = "shiny";
        public float multiplier = 2f;
        public int minutes = 30;
        public List<String> species = List.of();
    }

    public static EventSchedule defaults() {
        return new EventSchedule();
    }
}
