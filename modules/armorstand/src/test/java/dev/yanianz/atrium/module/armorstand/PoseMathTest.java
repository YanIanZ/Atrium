package dev.yanianz.atrium.module.armorstand;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Random;
import org.bukkit.util.EulerAngle;
import org.junit.jupiter.api.Test;

class PoseMathTest {
    @Test void stepIsConvertedWithToRadiansNotIntegerDivision() {
        // Integer-division pitfall: PI / (360 / v) would make 1° steps 0.5° and 7° steps ~3.53°.
        assertEquals(Math.toRadians(1), PoseMath.stepRadians(1), 1e-15);
        assertEquals(Math.toRadians(7), PoseMath.stepRadians(7), 1e-15);
        assertEquals(Math.toRadians(-10), PoseMath.stepRadians(-10), 1e-15);
        assertEquals(Math.toRadians(0.5), PoseMath.stepRadians(0.5), 1e-15);
        assertEquals(Math.PI, PoseMath.stepRadians(180), 1e-15);
    }

    @Test void rejectsStepsThatCouldProduceInfinityOrNaN() {
        for (double bad : new double[]{0, 181, -400, 720, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PoseMath.stepRadians(bad), "step " + bad);
        }
    }

    @Test void rotatingAddsExactDegreesAndStaysBounded() {
        EulerAngle angle = EulerAngle.ZERO;
        for (int i = 0; i < 1000; i++) {
            angle = PoseMath.rotate(angle, Pose.Axis.X, 45);
            assertTrue(angle.getX() >= -Math.PI && angle.getX() < Math.PI, "x=" + angle.getX());
        }
        // 1000 * 45 = 45000 = 125 full turns -> back to 0.
        assertEquals(0.0, Pose.Angles.of(angle).x());
        EulerAngle one = PoseMath.rotate(EulerAngle.ZERO, Pose.Axis.Y, 1);
        assertEquals(1.0, Pose.Angles.of(one).y());
        assertEquals(0.0, one.getX());
        assertEquals(0.0, one.getZ());
        EulerAngle back = PoseMath.rotate(PoseMath.rotate(EulerAngle.ZERO, Pose.Axis.Z, 170), Pose.Axis.Z, 20);
        assertEquals(-170.0, Pose.Angles.of(back).z());
    }

    @Test void canonicalDegreesWrapsAndRoundsToHundredths() {
        assertEquals(12.35, PoseMath.canonicalDegrees(12.345000001));
        assertEquals(-180.0, PoseMath.canonicalDegrees(180));
        assertEquals(-180.0, PoseMath.canonicalDegrees(179.999));
        assertEquals(0.0, PoseMath.canonicalDegrees(-0.001));
        assertEquals(10.0, PoseMath.canonicalDegrees(370));
        assertEquals(-14.99, PoseMath.canonicalDegrees(-14.99));
        assertEquals(170.0, PoseMath.canonicalDegrees(-190));
        assertThrows(IllegalArgumentException.class, () -> PoseMath.canonicalDegrees(Double.NaN));
    }

    @Test void encodeDecodeRoundTripIsStable() {
        Random random = new Random(42);
        for (int n = 0; n < 2000; n++) {
            EnumMap<Pose.Part, Pose.Angles> map = new EnumMap<>(Pose.Part.class);
            for (Pose.Part part : Pose.Part.values()) {
                EulerAngle raw = new EulerAngle(random.nextDouble(-20, 20), random.nextDouble(-20, 20),
                        random.nextDouble(-20, 20));
                map.put(part, Pose.Angles.of(raw));
            }
            Pose pose = new Pose(map);
            Pose decoded = Pose.decode(pose.encode());
            assertEquals(pose, decoded);
            // Applying to a stand goes through radians; reading back must compare equal with the same rounding.
            for (Pose.Part part : Pose.Part.values()) {
                EulerAngle applied = decoded.get(part).toEuler();
                assertEquals(decoded.get(part), Pose.Angles.of(applied));
                assertTrue(PoseMath.same(applied, pose.get(part).toEuler()));
            }
            assertEquals(pose.encode(), decoded.encode());
        }
    }

    @Test void halfDegreeStepsSurviveSaveAndLoad() {
        // Truncating to whole degrees on save would shift the pose after load.
        EulerAngle angle = EulerAngle.ZERO;
        for (int i = 0; i < 3; i++) {
            angle = PoseMath.rotate(angle, Pose.Axis.X, 0.5);
        }
        EnumMap<Pose.Part, Pose.Angles> map = new EnumMap<>(Pose.Part.class);
        map.put(Pose.Part.RIGHT_ARM, Pose.Angles.of(angle));
        Pose loaded = Pose.decode(new Pose(map).encode());
        assertEquals(1.5, loaded.get(Pose.Part.RIGHT_ARM).x());
    }

    @Test void decodeRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Pose.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Pose.decode("head:1,2"));
        assertThrows(IllegalArgumentException.class, () -> Pose.decode("tail:1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> Pose.decode("head:NaN,0,0"));
        assertThrows(IllegalArgumentException.class, () -> Pose.decode("head"));
    }

    @Test void defaultPoseMatchesVanillaArms() {
        assertEquals(new Pose.Angles(-10, 0, -10), Pose.DEFAULT.get(Pose.Part.LEFT_ARM));
        assertEquals(Pose.Angles.ZERO, Pose.DEFAULT.get(Pose.Part.HEAD));
    }
}
