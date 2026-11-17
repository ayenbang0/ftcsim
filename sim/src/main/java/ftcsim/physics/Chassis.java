package ftcsim.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * Rigid-body model of the robot chassis driven by a mecanum or tank drivetrain.
 * Positions are in the field frame (metres, radians); forces from the wheel
 * motors are computed with a DC motor model and a per-wheel traction limit.
 */
public final class Chassis {
    public enum DriveType { MECANUM, TANK, NONE }

    public DriveType driveType = DriveType.NONE;
    public double mass = 12.0;           // kg
    public double length = 18 * Units.INCH, width = 18 * Units.INCH; // m
    public double inertiaZ;              // kg m^2
    public double wheelRadius = 0.048;   // m
    public double halfWheelBase = 6 * Units.INCH;   // lx, m (front/back distance from centre)
    public double halfTrackWidth = 7 * Units.INCH;  // ly, m
    public double gearRatio = 1.0;       // wheel revolutions per motor output shaft revolution
    public double tractionCoefficient = 0.75;
    // friction on the body (N / N m): coulomb + viscous per axis
    public double coulombX = 10, coulombY = 20, coulombTheta = 0.8;
    public double viscousX = 2.0, viscousY = 4.0, viscousTheta = 0.15;
    /** Robot height, metres: solids whose underside is above it can be driven under (R102: 18 in at the start of a match). */
    public double height = 18 * Units.INCH;

    /** Mecanum order: leftFront, rightFront, leftRear, rightRear. Tank: left then right side lists. */
    public MotorState leftFront, rightFront, leftRear, rightRear;
    public final List<MotorState> leftMotors = new ArrayList<>();
    public final List<MotorState> rightMotors = new ArrayList<>();

    // ---- state ----
    private Pose2 pose = new Pose2(0, 0, Math.PI / 2);
    private Vec2 velocityField = Vec2.ZERO;
    private double omega;
    private Vec2 lastAccelRobot = Vec2.ZERO;
    private double lastDriveCurrent;
    private volatile int teleportEpoch;

    public void computeInertia() { inertiaZ = mass * (length * length + width * width) / 12.0; }

    public synchronized Pose2 pose() { return pose; }
    public synchronized void setPose(Pose2 p) { pose = p; velocityField = Vec2.ZERO; omega = 0; teleportEpoch++; }
    /** Incremented every time the robot is teleported (UI drag, pose reset, snap to code pose). */
    public int teleportEpoch() { return teleportEpoch; }
    public synchronized Vec2 velocityField() { return velocityField; }
    public synchronized Vec2 velocityRobot() { return velocityField.rotated(-pose.heading); }
    public synchronized double omega() { return omega; }
    public synchronized Vec2 accelerationRobot() { return lastAccelRobot; }
    public double driveCurrent() { return lastDriveCurrent; }
    public synchronized void stop() { velocityField = Vec2.ZERO; omega = 0; }

    public List<MotorState> driveMotors() {
        List<MotorState> l = new ArrayList<>();
        if (driveType == DriveType.MECANUM) { if (leftFront != null) l.add(leftFront); if (rightFront != null) l.add(rightFront); if (leftRear != null) l.add(leftRear); if (rightRear != null) l.add(rightRear); }
        else if (driveType == DriveType.TANK) { l.addAll(leftMotors); l.addAll(rightMotors); }
        return l;
    }

    public synchronized void step(double dt, double batteryVolts, Field field) {
        if (inertiaZ == 0) computeInertia();
        Vec2 vr = velocityField.rotated(-pose.heading);
        double fx = 0, fy = 0, tz = 0;
        double totalCurrent = 0;
        double normalPerWheel;
        if (driveType == DriveType.MECANUM) {
            MotorState[] w = { leftFront, rightFront, leftRear, rightRear };
            double k = halfWheelBase + halfTrackWidth;
            double[] rim = { vr.x - vr.y - k * omega, vr.x + vr.y + k * omega, vr.x + vr.y - k * omega, vr.x - vr.y + k * omega };
            normalPerWheel = mass * 9.81 / 4.0;
            double[] f = new double[4];
            for (int i = 0; i < 4; i++) {
                if (w[i] == null) continue;
                double shaft = rim[i] / wheelRadius / gearRatio;
                w[i].observeShaftSpeed(shaft, dt);
                double tauShaft = w[i].torqueStep(dt, batteryVolts);
                double force = tauShaft / gearRatio / wheelRadius;
                double limit = tractionCoefficient * normalPerWheel;
                f[i] = Math.max(-limit, Math.min(limit, force));
                totalCurrent += w[i].currentAmps();
            }
            fx = f[0] + f[1] + f[2] + f[3];
            fy = -f[0] + f[1] + f[2] - f[3];
            tz = k * (-f[0] + f[1] - f[2] + f[3]);
        } else if (driveType == DriveType.TANK) {
            int n = Math.max(1, leftMotors.size() + rightMotors.size());
            normalPerWheel = mass * 9.81 / n;
            vr = new Vec2(vr.x, 0); // no lateral slip for traction wheels
            double fl = 0, fr = 0;
            for (MotorState m : leftMotors) {
                double shaft = (vr.x - halfTrackWidth * omega) / wheelRadius / gearRatio;
                m.observeShaftSpeed(shaft, dt);
                double force = m.torqueStep(dt, batteryVolts) / gearRatio / wheelRadius;
                double limit = tractionCoefficient * normalPerWheel;
                fl += Math.max(-limit, Math.min(limit, force));
                totalCurrent += m.currentAmps();
            }
            for (MotorState m : rightMotors) {
                double shaft = (vr.x + halfTrackWidth * omega) / wheelRadius / gearRatio;
                m.observeShaftSpeed(shaft, dt);
                double force = m.torqueStep(dt, batteryVolts) / gearRatio / wheelRadius;
                double limit = tractionCoefficient * normalPerWheel;
                fr += Math.max(-limit, Math.min(limit, force));
                totalCurrent += m.currentAmps();
            }
            fx = fl + fr;
            tz = halfTrackWidth * (fr - fl);
        }
        lastDriveCurrent = totalCurrent;

        // Body friction (robot frame). Coulomb friction is limited so it never reverses motion.
        double ffx = frictionForce(vr.x, fx, coulombX, viscousX, mass, dt);
        double ffy = frictionForce(vr.y, fy, coulombY, viscousY, mass, dt);
        double tf = frictionForce(omega, tz, coulombTheta, viscousTheta, inertiaZ, dt);

        Vec2 aRobot = new Vec2((fx + ffx) / mass, (fy + ffy) / mass);
        lastAccelRobot = aRobot;
        Vec2 aField = aRobot.rotated(pose.heading);
        Vec2 vNew = velocityField.plus(aField.times(dt));
        if (driveType == DriveType.TANK) {
            // remove lateral component (kinematic constraint)
            Vec2 vRobotNew = vNew.rotated(-pose.heading);
            vNew = new Vec2(vRobotNew.x, 0).rotated(pose.heading);
        }
        double omegaNew = omega + (tz + tf) / inertiaZ * dt;
        velocityField = vNew;
        omega = omegaNew;
        double nx = pose.x + velocityField.x * dt;
        double ny = pose.y + velocityField.y * dt;
        double nh = Pose2.normalize(pose.heading + omega * dt);
        pose = new Pose2(nx, ny, nh);
        if (field != null) resolveCollisions(field);
    }

    /** Coulomb + viscous friction along one axis, with the coulomb part clamped so it only ever decelerates. */
    private static double frictionForce(double v, double driveForce, double coulomb, double viscous, double inertia, double dt) {
        double fv = -viscous * v;
        double fc;
        if (Math.abs(v) < 1e-6) {
            // static: cancel the drive force up to the coulomb limit
            fc = -Math.max(-coulomb, Math.min(coulomb, driveForce));
        } else {
            double stopForce = -inertia * v / dt; // force that would stop the body within dt
            fc = Math.abs(stopForce) < coulomb ? stopForce : -Math.signum(v) * coulomb;
        }
        return fv + fc;
    }

    private void resolveCollisions(Field field) {
        double hl = length / 2, hw = width / 2;
        double c = Math.cos(pose.heading), s = Math.sin(pose.heading);
        double half = field.halfSizeIn * Units.INCH;
        // perimeter walls: push the farthest corner back inside
        double[][] corners = { { hl, hw }, { hl, -hw }, { -hl, hw }, { -hl, -hw } };
        double pushX = 0, pushY = 0;
        for (double[] cr : corners) {
            double cx = pose.x + c * cr[0] - s * cr[1];
            double cy = pose.y + s * cr[0] + c * cr[1];
            if (cx > half) pushX = Math.min(pushX, half - cx);
            if (cx < -half) pushX = Math.max(pushX, -half - cx);
            if (cy > half) pushY = Math.min(pushY, half - cy);
            if (cy < -half) pushY = Math.max(pushY, -half - cy);
        }
        // solids: separating-axis test of the robot rectangle against each axis-aligned box (field x/y, robot u/v);
        // the axis of least overlap is the way out. Two passes so a push out of one box can't leave it inside the next.
        double reach = Math.hypot(hl, hw), ac = Math.abs(c), as = Math.abs(s);
        double rx = ac * hl + as * hw, ry = as * hl + ac * hw; // half extent of the robot on the field axes
        for (int pass = 0; pass < 2; pass++) {
            boolean moved = false;
            for (Field.Obstacle o : field.obstacles) {
                if (o.minZ * Units.INCH >= height) continue; // the robot drives underneath
                double ex = (o.maxX - o.minX) / 2 * Units.INCH, ey = (o.maxY - o.minY) / 2 * Units.INCH;
                double dx = pose.x + pushX - (o.minX + o.maxX) / 2 * Units.INCH, dy = pose.y + pushY - (o.minY + o.maxY) / 2 * Units.INCH;
                if (Math.abs(dx) >= ex + reach || Math.abs(dy) >= ey + reach) continue;
                double du = dx * c + dy * s, dv = -dx * s + dy * c; // robot centre relative to the box, on the robot axes
                double o1 = rx + ex - Math.abs(dx), o2 = ry + ey - Math.abs(dy);
                double o3 = hl + ex * ac + ey * as - Math.abs(du), o4 = hw + ex * as + ey * ac - Math.abs(dv);
                if (o1 <= 0 || o2 <= 0 || o3 <= 0 || o4 <= 0) continue; // a separating axis exists
                double nx, ny, depth;
                if (o1 <= o2 && o1 <= o3 && o1 <= o4) { nx = dx >= 0 ? 1 : -1; ny = 0; depth = o1; }
                else if (o2 <= o3 && o2 <= o4) { nx = 0; ny = dy >= 0 ? 1 : -1; depth = o2; }
                else if (o3 <= o4) { double sg = du >= 0 ? 1 : -1; nx = c * sg; ny = s * sg; depth = o3; }
                else { double sg = dv >= 0 ? 1 : -1; nx = -s * sg; ny = c * sg; depth = o4; }
                pushX += nx * depth; pushY += ny * depth;
                moved = true;
            }
            if (!moved) break;
        }
        if (pushX != 0 || pushY != 0) {
            pose = new Pose2(pose.x + pushX, pose.y + pushY, pose.heading);
            Vec2 n = new Vec2(pushX, pushY);
            double nn = n.norm();
            if (nn > 0) {
                Vec2 unit = n.times(1 / nn);
                double vn = velocityField.dot(unit);
                if (vn < 0) velocityField = velocityField.minus(unit.times(vn)); // kill the velocity into the wall
                omega *= 0.5;
            }
        }
    }
}
