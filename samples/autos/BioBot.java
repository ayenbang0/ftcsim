import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * Small helper shared by the sample BIOBUZZ OpModes: a mecanum drive with a closed-loop goTo() based on a
 * SparkFun OTOS, an intake motor, a flywheel and a feeder servo.
 *
 * Field frame (FTC, centre origin, inches): the RED wall is at y = -70.6, the BLUE wall at y = +70.6, the audience is
 * on +x, heading 0 deg points at +x and increases counter-clockwise.
 * Hardware names match configs/Biobuzz.json.
 */
public class BioBot {
    public final DcMotorEx fl, fr, bl, br, intake, shooter;
    public final Servo feeder;
    public final SparkFunOTOS otos;
    private final LinearOpMode op;

    public BioBot(LinearOpMode op, double startX, double startY, double startHeadingDeg) {
        this.op = op;
        fl = op.hardwareMap.get(DcMotorEx.class, "frontLeft");
        fr = op.hardwareMap.get(DcMotorEx.class, "frontRight");
        bl = op.hardwareMap.get(DcMotorEx.class, "backLeft");
        br = op.hardwareMap.get(DcMotorEx.class, "backRight");
        intake = op.hardwareMap.get(DcMotorEx.class, "intake");
        shooter = op.hardwareMap.get(DcMotorEx.class, "shooter");
        feeder = op.hardwareMap.get(Servo.class, "feeder");
        otos = op.hardwareMap.get(SparkFunOTOS.class, "otos");
        fl.setDirection(DcMotorSimple.Direction.REVERSE);
        bl.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[] {fl, fr, bl, br}) {
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }
        shooter.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        feeder.setPosition(0.0);
        otos.setLinearUnit(DistanceUnit.INCH);
        otos.setAngularUnit(AngleUnit.DEGREES);
        otos.calibrateImu();
        otos.resetTracking();
        otos.setPosition(new SparkFunOTOS.Pose2D(startX, startY, startHeadingDeg));
    }

    public double x() { return otos.getPosition().x; }
    public double y() { return otos.getPosition().y; }
    public double heading() { return otos.getPosition().h; }

    /** Robot-centric mecanum drive: forward and strafeRight in -1..1, turnCw clockwise positive. */
    public void drive(double forward, double strafeRight, double turnCw) {
        double d = Math.max(1.0, Math.abs(forward) + Math.abs(strafeRight) + Math.abs(turnCw));
        fl.setPower((forward + strafeRight + turnCw) / d);
        fr.setPower((forward - strafeRight - turnCw) / d);
        bl.setPower((forward - strafeRight + turnCw) / d);
        br.setPower((forward + strafeRight - turnCw) / d);
    }

    public void stop() { drive(0, 0, 0); }

    private static double wrap(double deg) {
        while (deg > 180) deg -= 360;
        while (deg <= -180) deg += 360;
        return deg;
    }

    /** Drive to a field pose with a proportional controller; returns when settled or after timeoutS. */
    public boolean goTo(double tx, double ty, double thDeg, double maxPower, double timeoutS) {
        long t0 = System.nanoTime();
        int settled = 0;
        while (op.opModeIsActive() && (System.nanoTime() - t0) / 1e9 < timeoutS) {
            SparkFunOTOS.Pose2D p = otos.getPosition();
            double dx = tx - p.x, dy = ty - p.y, eh = wrap(thDeg - p.h);
            double h = Math.toRadians(p.h);
            double f = dx * Math.cos(h) + dy * Math.sin(h);
            double s = dx * Math.sin(h) - dy * Math.cos(h);
            double dist = Math.hypot(dx, dy);
            double scale = dist > 1e-6 ? Math.min(maxPower, Math.max(0.12, dist * 0.04)) / dist : 0;
            double turn = Math.max(-0.5, Math.min(0.5, -eh * 0.02));
            if (dist < 0.8) { f = 0; s = 0; scale = 0; }
            if (Math.abs(eh) < 1.5) turn = 0;
            drive(f * scale, s * scale, turn);
            if (dist < 0.8 && Math.abs(eh) < 1.5) { if (++settled > 5) break; } else settled = 0;
            op.sleep(10);
        }
        stop();
        return settled > 5;
    }

    /** Spin the flywheel up and feed {@code count} held elements into the launcher. */
    public void shoot(int count, double flywheelPower) {
        shooter.setPower(flywheelPower);
        op.sleep(1500);
        for (int i = 0; i < count && op.opModeIsActive(); i++) {
            feeder.setPosition(1.0);
            op.sleep(350);
            feeder.setPosition(0.0);
            op.sleep(450);
        }
        shooter.setPower(0);
    }
}
