package ftcsim.game;

/** One POLLEN or NECTAR. Position in inches in the field frame (+X audience, +Y blue wall, +Z up), velocity in inches per second. */
public final class Element {
    public enum Type { POLLEN, NECTAR }

    /**
     * FIELD: on the floor or rolling; FLIGHT: in the air; HELD: inside a robot; FLOWER / CELL: stacked in a FLOWER or lying in a HIVE CELL;
     * HUMAN: staged off the field; OUT: left the field and out of play.
     */
    public enum State { FIELD, FLIGHT, HELD, FLOWER, CELL, HUMAN, OUT }

    public static final double POLLEN_RADIUS = 1.4, NECTAR_RADIUS = 1.81;

    public final int id;
    public final Type type;
    /** NECTAR colour; null for POLLEN. */
    public final Alliance alliance;
    public final double r;

    public State state;
    public double x, y, z, vx, vy, vz;
    /** Position at the start of the current step (crossing tests). */
    public double px, py, pz;
    public boolean asleep;
    /** Shot by a launcher and not yet at rest (only LAUNCHed elements may enter a HIVE CELL, G417). */
    public boolean launched;
    /** Thrown out of a tipping HIVE and not yet touched anything (G409). */
    public boolean released;
    /** Seconds the element has been slower than the sleep threshold on the floor. */
    public double restTime;
    /** Index of the HIVE or FLOWER holding the element (CELL / FLOWER). */
    public int container = -1;
    /** The robot does not collide with this element until this sim time (just fired from inside the robot). */
    public double ignoreRobotUntil;

    final State state0;
    final double x0, y0, z0;
    final int container0;

    Element(int id, Type type, Alliance alliance, double x, double y, double z, State state, int container) {
        this.id = id;
        this.type = type;
        this.alliance = alliance;
        this.r = type == Type.NECTAR ? NECTAR_RADIUS : POLLEN_RADIUS;
        this.x0 = x; this.y0 = y; this.z0 = z;
        this.state0 = state;
        this.container0 = container;
        restore();
    }

    void restore() {
        x = px = x0; y = py = y0; z = pz = z0;
        vx = vy = vz = 0;
        state = state0;
        container = container0;
        asleep = true;
        launched = released = false;
        restTime = 0;
        ignoreRobotUntil = 0;
    }

    /** Contribution to the load that tips a HIVE (POLLEN 1.0, NECTAR 1.3). */
    public double weight() { return type == Type.NECTAR ? 1.3 : 1.0; }

    public double diameter() { return 2 * r; }

    public boolean airborneOrMoving() { return !asleep && (state == State.FIELD || state == State.FLIGHT); }
}
