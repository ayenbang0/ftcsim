package ftcsim.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The BIOBUZZ rule catalogue (BIOBUZZ Competition Manual TU08, sections 10.6, 11 and 12) as far as a single simulated robot can break it,
 * and the foul ledger that turns rule breaks into VERBAL WARNINGs, MINOR / MAJOR FOULs and cards.
 *
 * <p>The manual leaves STRATEGIC and REPEATED to the head referee. The ledger uses a fixed, documented stand-in: the first two occurrences of a
 * rule are VERBAL WARNINGs, from the third on (or at once when the detector says the behaviour is clearly advantage-seeking) the break is treated as
 * STRATEGIC and escalates the way the manual says for that rule.
 */
public final class Rules {
    private Rules() {}

    public static final double AUTO_S = 30, TRANSITION_S = 8, TELEOP_S = 120;
    /** NECTAR may enter a FLOWER once this much TELEOP time is left (G410); human NECTAR is released in bulk then too (G426). */
    public static final double FLOWER_UNLOCK_REMAINING_S = 60;
    public static final int MINOR_POINTS = 5, MAJOR_POINTS = 20;
    public static final int LEAVE_POINTS = 3, PARK_POINTS = 5, TIP_POINTS = 20, CELL_ELEMENT_POINTS = 2, FLOWER_ELEMENT_POINTS = 2,
            BOTTOM_NECTAR_POINTS = 5, GARDEN_ELEMENT_POINTS = 1;
    public static final int SWARM_THRESHOLD = 16, POLLINATOR_1_TIPS = 4, POLLINATOR_2_TIPS = 7;

    public enum Level { WARNING, MINOR, MAJOR, YELLOW, RED, DISABLED }

    /** How a rule escalates. */
    public enum Policy {
        /** VERBAL WARNING, STRATEGIC: MAJOR FOUL and YELLOW CARD (G401, G403, G404, G407, G417, G418). */
        WARN_MAJOR_YELLOW,
        /** VERBAL WARNING, STRATEGIC: YELLOW CARD (G408, G409). */
        WARN_YELLOW,
        /** One MAJOR FOUL per occurrence (G410). */
        MAJOR_EACH,
        /** One MAJOR FOUL per MATCH (G402). */
        MAJOR_ONCE,
        /** One MINOR FOUL per occurrence (G426, G427, G428). */
        MINOR_EACH,
        /** Reported only: the manual's consequence cannot be applied to a simulated robot (G405, R-rules). */
        ADVISORY
    }

    public static final class Rule {
        public final String id, title;
        public final Policy policy;

        Rule(String id, String title, Policy policy) { this.id = id; this.title = title; this.policy = policy; }
    }

    private static final Map<String, Rule> CATALOGUE = new LinkedHashMap<>();

    private static void rule(String id, String title, Policy p) { CATALOGUE.put(id, new Rule(id, title, p)); }

    static {
        rule("R102", "Start configuration inside 18 x 18 x 18 in", Policy.ADVISORY);
        rule("R503", "At most 8 motors and 8 servos", Policy.ADVISORY);
        rule("R701", "One Control Hub and at most one Expansion Hub", Policy.ADVISORY);
        rule("G401", "No DRIVE TEAM interaction with the ROBOT during AUTO", Policy.WARN_MAJOR_YELLOW);
        rule("G402", "No AUTO opponent interference", Policy.MAJOR_ONCE);
        rule("G403", "ROBOTS are motionless between AUTO and TELEOP", Policy.WARN_MAJOR_YELLOW);
        rule("G404", "ROBOTS are motionless at the end of TELEOP", Policy.WARN_MAJOR_YELLOW);
        rule("G405", "Keep SCORING ELEMENTS in bounds", Policy.ADVISORY);
        rule("G407", "No more than 4 SCORING ELEMENTS at a time", Policy.WARN_MAJOR_YELLOW);
        rule("G408", "Don't CONTROL opponent NECTAR", Policy.WARN_YELLOW);
        rule("G409", "Don't catch SCORING ELEMENTS released by a tipped HIVE", Policy.WARN_YELLOW);
        rule("G410", "NECTAR only goes into FLOWERS with one minute left", Policy.MAJOR_EACH);
        rule("G417", "ROBOTS may not meddle with the HIVE structure", Policy.WARN_MAJOR_YELLOW);
        rule("G418", "ROBOTS may not meddle with FLOWERS", Policy.WARN_MAJOR_YELLOW);
        rule("G426", "Humans may not enter NECTAR onto the FIELD early", Policy.MINOR_EACH);
        rule("G427", "Humans only enter NECTAR through the LOADING ZONE", Policy.MINOR_EACH);
        rule("G428", "Humans may not remove SCORING ELEMENTS from the FIELD", Policy.MINOR_EACH);
    }

    public static Rule rule(String id) { return CATALOGUE.get(id); }

    public static List<Rule> all() { return new ArrayList<>(CATALOGUE.values()); }

    /** One line of the violation log. */
    public static final class Violation {
        /** Match time (seconds since the start of AUTO) at which the violation was called. */
        public final double t;
        public final String phase, rule, note;
        public final Level level;
        /** The alliance whose robot or drive team broke the rule. */
        public final Alliance against;

        Violation(double t, String phase, String rule, Level level, Alliance against, String note) {
            this.t = t; this.phase = phase; this.rule = rule; this.level = level; this.against = against; this.note = note;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", Math.round(t * 100) / 100.0);
            m.put("phase", phase);
            m.put("rule", rule);
            m.put("level", level.name());
            m.put("against", against.name());
            m.put("note", note);
            return m;
        }
    }

    /** The running record of calls against the robot, and the points those calls hand to the other alliance. */
    public static final class Ledger {
        private static final int MAX_LOG = 50;

        private final List<Violation> log = new ArrayList<>();
        private final int[] points = new int[2];
        private final boolean[] disqualified = new boolean[2];
        private final int[] yellows = new int[2];
        private final Map<String, Integer> counts = new HashMap<>();
        private final Set<String> carded = new HashSet<>();
        private final Set<String> majored = new HashSet<>();

        public synchronized void clear() {
            log.clear();
            Arrays.fill(points, 0);
            Arrays.fill(disqualified, false);
            Arrays.fill(yellows, 0);
            counts.clear();
            carded.clear();
            majored.clear();
        }

        /** Points awarded to {@code alliance} because of fouls committed by the other alliance. */
        public synchronized int pointsFor(Alliance alliance) { return points[alliance.ordinal()]; }

        public synchronized boolean disqualified(Alliance alliance) { return disqualified[alliance.ordinal()]; }

        public synchronized int count(String rule) { return counts.getOrDefault(rule, 0); }

        public synchronized List<Violation> log() { return new ArrayList<>(log); }

        /**
         * Records a rule break by {@code offender}.
         *
         * @param strategic true when the detector judges the behaviour advantage-seeking, so the first-offence VERBAL WARNING is skipped
         * @return the lines that were added to the log (empty when nothing is called, for example a second G402)
         */
        public synchronized List<Violation> violate(double t, String phase, String rule, Alliance offender, String detail, boolean strategic) {
            Rule r = CATALOGUE.get(rule);
            if (r == null) throw new IllegalArgumentException("unknown rule " + rule);
            int n = counts.merge(rule, 1, Integer::sum);
            String note = r.title + (detail == null || detail.isEmpty() ? "" : ": " + detail);
            List<Violation> added = new ArrayList<>();
            boolean strategicNow = strategic || n >= 3;
            switch (r.policy) {
                case ADVISORY:
                    added.add(add(t, phase, rule, Level.WARNING, offender, note, 0));
                    break;
                case MINOR_EACH:
                    added.add(add(t, phase, rule, Level.MINOR, offender, note, MINOR_POINTS));
                    break;
                case MAJOR_EACH:
                    added.add(add(t, phase, rule, Level.MAJOR, offender, note, MAJOR_POINTS));
                    break;
                case MAJOR_ONCE:
                    if (majored.add(rule)) added.add(add(t, phase, rule, Level.MAJOR, offender, note, MAJOR_POINTS));
                    break;
                case WARN_MAJOR_YELLOW:
                    if (!strategicNow) {
                        added.add(add(t, phase, rule, Level.WARNING, offender, "VERBAL WARNING - " + note, 0));
                    } else {
                        if (majored.add(rule)) added.add(add(t, phase, rule, Level.MAJOR, offender, "STRATEGIC - " + note, MAJOR_POINTS));
                        card(t, phase, rule, offender, added);
                    }
                    break;
                case WARN_YELLOW:
                    if (!strategicNow) {
                        added.add(add(t, phase, rule, Level.WARNING, offender, "VERBAL WARNING - " + note, 0));
                    } else {
                        card(t, phase, rule, offender, added);
                    }
                    break;
                default:
                    break;
            }
            return added;
        }

        private void card(double t, String phase, String rule, Alliance offender, List<Violation> added) {
            if (!carded.add(rule)) return; // the card is per MATCH: one for each rule
            int o = offender.ordinal();
            yellows[o]++;
            if (yellows[o] >= 2) {
                disqualified[o] = true;
                added.add(add(t, phase, rule, Level.RED, offender, "second YELLOW CARD becomes a RED CARD: DISQUALIFIED from the MATCH", 0));
            } else {
                added.add(add(t, phase, rule, Level.YELLOW, offender, "YELLOW CARD (a second one is a RED CARD)", 0));
            }
        }

        private Violation add(double t, String phase, String rule, Level level, Alliance offender, String note, int pointsToOpponent) {
            Violation v = new Violation(t, phase, rule, level, offender, note);
            log.add(v);
            if (log.size() > MAX_LOG) log.remove(0);
            points[offender.other().ordinal()] += pointsToOpponent;
            return v;
        }
    }
}
