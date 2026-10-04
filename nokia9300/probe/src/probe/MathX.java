package probe;

/** CLDC 1.1 has no Math.log: natural logarithm by range reduction + atanh series. */
class MathX {
    static final double LN2 = 0.6931471805599453;

    static double ln(double x) {
        if (x <= 0) return Double.NaN;
        int k = 0;
        while (x >= 2) { x /= 2; k++; }
        while (x < 1) { x *= 2; k--; }
        double y = (x - 1) / (x + 1), y2 = y * y, term = y, sum = 0;
        for (int i = 1; i < 60; i += 2) {
            sum += term / i;
            term *= y2;
        }
        return k * LN2 + 2 * sum;
    }
}
