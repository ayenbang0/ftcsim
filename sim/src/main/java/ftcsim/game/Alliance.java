package ftcsim.game;

/** The two ALLIANCES. RED owns the -Y wall of the BIOBUZZ field, BLUE the +Y wall. */
public enum Alliance {
    RED, BLUE;

    public Alliance other() { return this == RED ? BLUE : RED; }

    /** The side of the field this alliance starts on: -1 for RED (-Y), +1 for BLUE (+Y). */
    public int side() { return this == RED ? -1 : 1; }

    public static Alliance ofY(double y) { return y < 0 ? RED : BLUE; }

    public static Alliance parse(String s, Alliance def) {
        if (s == null) return def;
        switch (s.trim().toUpperCase()) {
            case "RED": case "R": return RED;
            case "BLUE": case "B": return BLUE;
            default: return def;
        }
    }
}
