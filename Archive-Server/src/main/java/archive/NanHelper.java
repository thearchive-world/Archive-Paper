package archive;

public class NanHelper {
    public static double ensureNonNaN(double value) {
        return Double.isNaN(value) ? 0.0D : value;
    }

    public static float ensureNonNaN(float value) {
        return Float.isNaN(value) ? 0.0F : value;
    }

    public static boolean isInvalid(double... value) {
        for (double v : value) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInvalid(float... value) {
        for (float v : value) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return true;
            }
        }
        return false;
    }
}
