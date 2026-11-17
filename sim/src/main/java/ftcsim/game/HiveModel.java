package ftcsim.game;

import ftcsim.map.FieldMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One HIVE: a bi-stable rocker with a CELL at each end, built from the CAD map ({@link FieldMap.Hive}).
 *
 * <p>The rocker is rigid and turns about an axis parallel to the field Y axis through (hive.x, pivotZ). Each CELL is a box of
 * {@code depth x height x width} with its mouth on the side the map's mouth vector points to; the up-facing CELL accepts LAUNCHED elements through
 * its mouth, a CELL holding enough weight tips the HIVE, and the CELL that swings down spills what it holds in front of the HIVE.
 *
 * <p>Frames: the cell frame has u along the mouth normal (the mouth plane is u = depth/2, the back wall u = -depth/2), v perpendicular in the
 * XZ plane and pointing upward ("up" for a CELL whose mouth faces the audience side and for one that faces away), and w along Y from the HIVE centre.
 */
final class HiveModel {
    interface Listener {
        void tipStarted(HiveModel hive);

        void tipCompleted(HiveModel hive);
    }

    static final double TIP_SECONDS = 0.8, SPILL_AT_S = 0.30;
    private static final int[] COLUMN_ORDER = {2, 1, 3, 0, 4};
    private static final double SLOT_PITCH = 3.9, LAYER_PITCH = 3.7;

    final int index;
    final Alliance alliance;
    final FieldMap.Hive hive;
    final double tiltDeg;
    /** Rocker angle of the CAD model: positive when the AUDIENCE CELL is up. */
    final double angle0;

    /** CAD-state cell geometry relative to the pivot: centre (x, z), mouth normal, depth along the mouth, height across it, and the v orientation. */
    private final double[] cx = new double[2], cz = new double[2], mx = new double[2], mz = new double[2];
    private final double[] depth = new double[2], height = new double[2], sgn = new double[2], reach = new double[2];
    private final List<List<Element>> cells = new ArrayList<>();
    private final Listener listener;
    private final Random rng;

    /** Current rocker angle in degrees; positive when the AUDIENCE CELL is up. */
    double angle;
    /** Logical up side: flips the moment a tip starts. */
    boolean audienceUp;
    boolean tipping, spilled;
    double tipT, tipFrom, tipTo;
    int tips;
    double tipLoad = 7.0;
    private double cos = 1, sin = 0;

    HiveModel(int index, FieldMap.Hive hive, Listener listener, Random rng) {
        this.index = index;
        this.hive = hive;
        this.alliance = Alliance.parse(hive.alliance, Alliance.RED);
        this.listener = listener;
        this.rng = rng;
        this.tiltDeg = hive.tiltDeg;
        this.angle0 = hive.cells.get(0).up ? tiltDeg : -tiltDeg;
        cells.add(new ArrayList<>());
        cells.add(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            FieldMap.Cell c = hive.cells.get(i);
            double wx = c.shell.maxX - c.shell.minX, wz = c.shell.maxZ - c.shell.minZ;
            double len = Math.hypot(c.mouthX, c.mouthZ);
            double ux = len > 1e-9 ? c.mouthX / len : (i == 0 ? 1 : -1), uz = len > 1e-9 ? c.mouthZ / len : 0;
            mx[i] = ux;
            mz[i] = uz;
            sgn[i] = ux >= 0 ? 1 : -1;
            double d = 12, hh = 14;
            double det = ux * ux - uz * uz;
            if (Math.abs(det) > 0.05) {
                double dd = (Math.abs(ux) * wx - Math.abs(uz) * wz) / det;
                double h2 = (Math.abs(ux) * wz - Math.abs(uz) * wx) / det;
                if (dd > 6 && dd < 20 && h2 > 8 && h2 < 24) { d = dd; hh = h2; }
            }
            depth[i] = d;
            height[i] = hh;
            cx[i] = c.shell.centerX() - hive.x;
            cz[i] = c.shell.centerZ() - hive.pivotZ;
            reach[i] = Math.sqrt(d * d / 4 + hh * hh / 4 + hive.halfWidthIn * hive.halfWidthIn) + 0.5;
        }
        reset(hive.cells.get(0).up);
    }

    /** Puts the rocker in a stable state and empties both CELLs (the caller puts the elements back). */
    void reset(boolean audienceUp) {
        this.audienceUp = audienceUp;
        this.angle = audienceUp ? tiltDeg : -tiltDeg;
        tipping = false;
        spilled = false;
        tipT = 0;
        tips = 0;
        cells.get(0).clear();
        cells.get(1).clear();
        updateTrig();
    }

    int upCell() { return audienceUp ? 0 : 1; }

    String upSide() { return audienceUp ? "AUDIENCE" : "FAR"; }

    int contained(int cell) { return cells.get(cell).size(); }

    /** Elements lying in the CELL that faces upward. */
    List<Element> upContents() { return cells.get(upCell()); }

    double load(int cell) {
        double sum = 0;
        for (Element e : cells.get(cell)) sum += e.weight();
        return sum;
    }

    private void updateTrig() {
        double d = Math.toRadians(angle - angle0);
        cos = Math.cos(d);
        sin = Math.sin(d);
    }

    double cellX(int i) { return hive.x + cx[i] * cos - cz[i] * sin; }

    double cellZ(int i) { return hive.pivotZ + cx[i] * sin + cz[i] * cos; }

    double mouthX(int i) { return mx[i] * cos - mz[i] * sin; }

    double mouthZ(int i) { return mx[i] * sin + mz[i] * cos; }

    private double upX(int i) { return -sgn[i] * mouthZ(i); }

    private double upZ(int i) { return sgn[i] * mouthX(i); }

    /** Adds an element to a CELL (setup, or a LAUNCHED element that entered the mouth). */
    void add(Element e, int cell) {
        e.state = Element.State.CELL;
        e.container = index;
        e.vx = e.vy = e.vz = 0;
        e.asleep = true;
        e.launched = false;
        e.released = false;
        cells.get(cell).add(e);
        place();
    }

    /** Slots every contained element into its place in the (moving) CELL. */
    void place() {
        updateTrig();
        for (int i = 0; i < 2; i++) {
            List<Element> l = cells.get(i);
            if (l.isEmpty()) continue;
            double ox = cellX(i), oz = cellZ(i), mxi = mouthX(i), mzi = mouthZ(i), vxi = upX(i), vzi = upZ(i);
            for (int k = 0; k < l.size(); k++) {
                Element e = l.get(k);
                int layer = k / 15, rem = k % 15, row = rem / 5, col = COLUMN_ORDER[rem % 5];
                double u = -depth[i] / 2 + 2.0 + row * SLOT_PITCH;
                double v = -height[i] / 2 + e.r + 0.2 + layer * LAYER_PITCH;
                double w = (col - 2) * SLOT_PITCH;
                e.x = ox + u * mxi + v * vxi;
                e.z = oz + u * mzi + v * vzi;
                e.y = hive.y + w;
                e.px = e.x;
                e.py = e.y;
                e.pz = e.z;
            }
        }
    }

    /** True (and the element now sits in the CELL) when a LAUNCHED element crossed the mouth of the up CELL inward during the step that just ran. */
    boolean capture(Element e) {
        if (tipping || !e.launched) return false;
        int i = upCell();
        double ox = cellX(i), oz = cellZ(i), mxi = mouthX(i), mzi = mouthZ(i), vxi = upX(i), vzi = upZ(i);
        double u0 = (e.px - ox) * mxi + (e.pz - oz) * mzi, u1 = (e.x - ox) * mxi + (e.z - oz) * mzi;
        double half = depth[i] / 2 + e.r;   // the sphere surface touching the mouth plane, so capture comes before collide() bounces it
        if (!(u0 > half && u1 <= half)) return false;
        double t = (u0 - half) / (u0 - u1);
        double v0 = (e.px - ox) * vxi + (e.pz - oz) * vzi, v1 = (e.x - ox) * vxi + (e.z - oz) * vzi;
        double w0 = e.py - hive.y, w1 = e.y - hive.y;
        double v = v0 + t * (v1 - v0), w = w0 + t * (w1 - w0);
        if (Math.abs(v) > height[i] / 2 - 0.7 * e.r) return false;
        if (Math.abs(w) > hive.halfWidthIn - 0.7 * e.r) return false;
        add(e, i);
        return true;
    }

    /** Pushes an element out of both CELL boxes (a missed shot bounces off the outside). Returns true on contact. */
    boolean collide(Element e) {
        boolean hit = false;
        for (int i = 0; i < 2; i++) hit |= collideCell(e, i);
        return hit;
    }

    private boolean collideCell(Element e, int i) {
        double ox = cellX(i), oz = cellZ(i);
        double dxw = e.x - ox, dzw = e.z - oz, dyw = e.y - hive.y;
        double lim = reach[i] + e.r;
        if (dxw * dxw + dzw * dzw + dyw * dyw > lim * lim) return false;
        double mxi = mouthX(i), mzi = mouthZ(i), vxi = upX(i), vzi = upZ(i);
        double u = dxw * mxi + dzw * mzi, v = dxw * vxi + dzw * vzi, w = dyw;
        double hu = depth[i] / 2, hv = height[i] / 2, hw = hive.halfWidthIn;
        double du = u - clamp(u, -hu, hu), dv = v - clamp(v, -hv, hv), dw = w - clamp(w, -hw, hw);
        double d2 = du * du + dv * dv + dw * dw;
        if (d2 >= e.r * e.r) return false;
        double nu, nv, nw, pen;
        if (d2 > 1e-9) {
            double d = Math.sqrt(d2);
            nu = du / d; nv = dv / d; nw = dw / d;
            pen = e.r - d;
        } else {
            double pu = hu - Math.abs(u), pv = hv - Math.abs(v), pw = hw - Math.abs(w);
            nu = nv = nw = 0;
            if (pu <= pv && pu <= pw) { nu = u >= 0 ? 1 : -1; pen = pu + e.r; }
            else if (pv <= pw) { nv = v >= 0 ? 1 : -1; pen = pv + e.r; }
            else { nw = w >= 0 ? 1 : -1; pen = pw + e.r; }
        }
        double nx = nu * mxi + nv * vxi, nz = nu * mzi + nv * vzi, ny = nw;
        e.x += nx * pen;
        e.y += ny * pen;
        e.z += nz * pen;
        double vn = e.vx * nx + e.vy * ny + e.vz * nz;
        if (vn < 0) {
            double k = (1 + (vn < -6 ? 0.3 : 0)) * vn;
            e.vx -= k * nx;
            e.vy -= k * ny;
            e.vz -= k * nz;
        }
        return true;
    }

    void step(double dt) {
        if (tipping) {
            tipT += dt;
            double s = Math.min(1, tipT / TIP_SECONDS);
            angle = tipFrom + (tipTo - tipFrom) * (s * s * (3 - 2 * s));
            updateTrig();
            if (!spilled && tipT >= SPILL_AT_S) {
                spilled = true;
                spill();
            }
            place();
            if (tipT >= TIP_SECONDS) {
                angle = tipTo;
                tipping = false;
                tips++;
                updateTrig();
                place();
                listener.tipCompleted(this);
            }
        } else if (load(upCell()) >= tipLoad - 1e-9) {
            tipping = true;
            tipT = 0;
            spilled = false;
            tipFrom = angle;
            tipTo = -angle;
            audienceUp = !audienceUp;
            listener.tipStarted(this);
        }
    }

    /** The CELL that is swinging down lets go of what it holds: elements leave the mouth in front of the HIVE. */
    private void spill() {
        int i = 1 - upCell();
        List<Element> l = cells.get(i);
        double ox = cellX(i), oz = cellZ(i), mxi = mouthX(i), mzi = mouthZ(i), vxi = upX(i), vzi = upZ(i);
        for (int k = 0; k < l.size(); k++) {
            Element e = l.get(k);
            int layer = k / 10, rem = k % 10, col = rem % 5, row = rem / 5;
            double out = depth[i] / 2 + 0.6 + e.r + layer * (2 * e.r + 0.2);
            double along = (row - 0.5) * 3.8, w = (col - 2) * 3.8;
            e.x = ox + out * mxi + along * vxi;
            e.z = oz + out * mzi + along * vzi;
            e.y = hive.y + w;
            e.px = e.x;
            e.py = e.y;
            e.pz = e.z;
            double speed = 25 + 20 * rng.nextDouble();
            e.vx = mxi * speed + (rng.nextDouble() - 0.5) * 8;
            e.vz = mzi * speed + (rng.nextDouble() - 0.5) * 8;
            e.vy = (rng.nextDouble() - 0.5) * 14;
            e.state = Element.State.FIELD;
            e.container = -1;
            e.asleep = false;
            e.restTime = 0;
            e.released = true;
            e.launched = false;
        }
        l.clear();
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
