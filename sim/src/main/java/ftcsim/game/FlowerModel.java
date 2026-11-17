package ftcsim.game;

import ftcsim.map.FieldMap;

import java.util.ArrayList;
import java.util.List;

/**
 * One FLOWER: a vertical tube in the perimeter wall. Elements go in through the opening on top and stack up from the floor; POLLEN
 * comes back out of the retrieval opening at the bottom (G418). Scoring looks at the stack between the middle ring and the top ring.
 */
final class FlowerModel {
    /** Space left between two stacked elements (matches the CAD: the four starting POLLEN sit at 1.4, 4.3, 7.2 and 10.1 in). */
    static final double GAP = 0.1;
    /** Extra horizontal tolerance of the opening beyond openingR - r: the throat is chamfered. */
    private static final double FUNNEL = 1.4;
    /** An element that ends up resting on the housing is pushed off towards the field with this acceleration (in/s^2). */
    private static final double NUDGE = 160;

    final int index;
    final FieldMap.Flower flower;
    /** Unit vector from the wall towards the field centre. */
    final double inX, inY;
    /** Distance from the FLOWER axis to the face of the housing that looks into the field. */
    final double faceDist;
    /** Bottom first. */
    final List<Element> stack = new ArrayList<>();

    FlowerModel(int index, FieldMap.Flower f) {
        this.index = index;
        this.flower = f;
        FieldMap.Box b = f.footprint;
        if (Math.abs(f.x) > Math.abs(f.y)) {
            inX = f.x > 0 ? -1 : 1;
            inY = 0;
            faceDist = b == null ? 3.5 : (f.x > 0 ? f.x - b.minX : b.maxX - f.x);
        } else {
            inX = 0;
            inY = f.y > 0 ? -1 : 1;
            faceDist = b == null ? 3.5 : (f.y > 0 ? f.y - b.minY : b.maxY - f.y);
        }
    }

    void reset() { stack.clear(); }

    int size() { return stack.size(); }

    /** Where a robot's intake has to be to pull POLLEN out of the retrieval opening. */
    double openingX() { return flower.x + inX * faceDist; }

    double openingY() { return flower.y + inY * faceDist; }

    /** Puts an element on top of the stack (setup, or a captured element). */
    void add(Element e) {
        e.state = Element.State.FLOWER;
        e.container = index;
        e.vx = e.vy = e.vz = 0;
        e.asleep = true;
        e.launched = false;
        e.released = false;
        stack.add(e);
        layout();
    }

    /** Packs the stack from the floor up. */
    void layout() {
        double z = flower.floorZ;
        for (Element e : stack) {
            z += e.r;
            e.x = flower.x;
            e.y = flower.y;
            e.z = z;
            e.px = e.x; e.py = e.y; e.pz = e.z;
            z += e.r + GAP;
        }
    }

    /** Height of the centre of {@code e} if it were added now. */
    double nextCentreZ(Element e) {
        double z = flower.floorZ;
        for (Element s : stack) z += s.diameter() + GAP;
        return z + e.r;
    }

    boolean full(Element e) { return nextCentreZ(e) > flower.topRingZ + 1.5; }

    /**
     * True (and the element is in the stack) when {@code e} dropped through the opening during the step that just ran: it has to cross
     * the plane of the housing top going down, close enough to the axis, and there has to be room left.
     */
    boolean capture(Element e) {
        if (e.state != Element.State.FIELD && e.state != Element.State.FLIGHT) return false;
        if (e.vz >= 0) return false;
        double plane = flower.topZ + e.r;
        if (!(e.pz >= plane && e.z < plane)) return false;
        double span = e.pz - e.z;
        double t = span > 1e-9 ? (e.pz - plane) / span : 1;
        double hx = e.px + (e.x - e.px) * t - flower.x;
        double hy = e.py + (e.y - e.py) * t - flower.y;
        double hspeed = Math.hypot(e.vx, e.vy);
        double tolerance = (Math.max(flower.openingR - e.r, 0) + FUNNEL) * (1 - Math.min(0.6, hspeed / 300.0));
        if (Math.hypot(hx, hy) > tolerance) return false;
        if (full(e)) return false;
        add(e);
        return true;
    }

    /** True when the element lies in the scoring volume: at least partly between the middle ring and the top ring. */
    boolean inVolume(Element e) {
        return e.z + e.r > flower.middleRingZ && e.z - e.r < flower.topRingZ;
    }

    /** Takes the lowest element out through the retrieval opening; only POLLEN fits through it. Null when there is nothing to take. */
    Element releaseBottom() {
        if (stack.isEmpty()) return null;
        Element e = stack.get(0);
        if (e.type != Element.Type.POLLEN) return null;
        stack.remove(0);
        layout();
        return e;
    }

    /** Alliance of the top-most NECTAR in the scoring volume: the owner of the FLOWER. Null when there is none. */
    Alliance owner() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Element e = stack.get(i);
            if (e.type == Element.Type.NECTAR && inVolume(e)) return e.alliance;
        }
        return null;
    }

    /** Alliance of the bottom-most NECTAR in the scoring volume (Bottom NECTAR Bonus). Null when there is none. */
    Alliance bottomNectarAlliance() {
        for (Element e : stack) {
            if (e.type == Element.Type.NECTAR && inVolume(e)) return e.alliance;
        }
        return null;
    }

    /** Elements in the scoring volume; they score for the owner. */
    int scoringCount() {
        int n = 0;
        for (Element e : stack) if (inVolume(e)) n++;
        return n;
    }

    /** True when the point is over the housing of this FLOWER (the footprint of the CAD model, grown by {@code margin}). */
    boolean overHousing(double x, double y, double margin) {
        FieldMap.Box b = flower.footprint;
        return b != null && x >= b.minX - margin && x <= b.maxX + margin && y >= b.minY - margin && y <= b.maxY + margin;
    }

    /** Pushes an element that came to rest on top of the housing off it towards the field: the top is not a shelf. */
    void nudge(Element e, double dt) {
        if (e.state != Element.State.FIELD) return;
        if (!overHousing(e.x, e.y, 0)) return;
        if (Math.abs(e.z - e.r - flower.topZ) > 0.4) return;
        e.vx += inX * NUDGE * dt;
        e.vy += inY * NUDGE * dt;
        e.asleep = false;
        e.restTime = 0;
    }
}
