import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

/**
 * BIOBUZZ TeleOp: robot-centric mecanum drive on gamepad 1.
 * Left stick = drive, right stick X = turn. Right bumper = intake in, left bumper = intake out (spit).
 * Right trigger = spin up the flywheel, A = feed one element (tap). WASD/arrow keys work as a gamepad in the UI.
 */
@TeleOp(name = "BIOBUZZ TeleOp", group = "BIOBUZZ")
public class BiobuzzTeleOp extends LinearOpMode {
    @Override
    public void runOpMode() {
        DcMotorEx fl = hardwareMap.get(DcMotorEx.class, "frontLeft");
        DcMotorEx fr = hardwareMap.get(DcMotorEx.class, "frontRight");
        DcMotorEx bl = hardwareMap.get(DcMotorEx.class, "backLeft");
        DcMotorEx br = hardwareMap.get(DcMotorEx.class, "backRight");
        DcMotorEx intake = hardwareMap.get(DcMotorEx.class, "intake");
        DcMotorEx shooter = hardwareMap.get(DcMotorEx.class, "shooter");
        Servo feeder = hardwareMap.get(Servo.class, "feeder");
        fl.setDirection(DcMotorSimple.Direction.REVERSE);
        bl.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[] {fl, fr, bl, br, intake, shooter}) m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        feeder.setPosition(0);
        waitForStart();
        long feedUntil = 0;
        boolean lastA = false;
        while (opModeIsActive()) {
            double f = -gamepad1.left_stick_y, s = gamepad1.left_stick_x, t = gamepad1.right_stick_x;
            double d = Math.max(1.0, Math.abs(f) + Math.abs(s) + Math.abs(t));
            fl.setPower((f + s + t) / d);
            fr.setPower((f - s - t) / d);
            bl.setPower((f - s + t) / d);
            br.setPower((f + s - t) / d);
            intake.setPower(gamepad1.right_bumper ? 1.0 : gamepad1.left_bumper ? -1.0 : 0.0);
            shooter.setPower(gamepad1.right_trigger > 0.1 ? 0.9 : 0.0);
            if (gamepad1.a && !lastA) feedUntil = System.currentTimeMillis() + 300;
            lastA = gamepad1.a;
            feeder.setPosition(System.currentTimeMillis() < feedUntil ? 1.0 : 0.0);
            telemetry.addData("held?", "see the match HUD");
            telemetry.update();
        }
    }
}
