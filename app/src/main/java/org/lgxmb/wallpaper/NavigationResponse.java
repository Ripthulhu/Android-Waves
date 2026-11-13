/* PS3 3.01 direction response. SPDX-License-Identifier: MIT
 * Port of LG-XMB ps3-particle-birth.js DpadResponse (horizontal input),
 * with the existing native camera basis and no event or gesture policy.
 */
package org.lgxmb.wallpaper;

import java.util.Arrays;
import static org.lgxmb.wallpaper.WaveSimulation.f;

final class NavigationResponse {
    private double velocity, brownianPosition, brownianVelocity, axisY;
    double strength, rotationY, brownian;
    boolean rotating;
    final double[] rotation = new double[16];

    NavigationResponse() { reset(); }

    void reset() {
        velocity = brownianPosition = brownianVelocity = axisY = 0;
        strength = rotationY = brownian = 0;
        rotating = false;
        Arrays.fill(rotation, 0);
        for (int i = 0; i < 16; i += 5) rotation[i] = 1;
    }

    /** One recovered host update: 0 left, 1 right, 4 no pending direction. */
    void step(int direction) {
        double impulse = 0, brownianImpulse = 0;
        if (direction == 0 || direction == 1) {
            axisY = direction == 0 ? -1 : 1;
            impulse = f(.03);
            brownianImpulse = f(.04);
        }
        velocity = f(madd(-velocity, .02, impulse) + velocity);
        strength = f(Math.max(0, Math.min(1, velocity)));
        rotationY = f(f(axisY * f(.000116654)) * strength);
        double drag = madd(-brownianVelocity, .4, brownianImpulse);
        double acceleration = madd(-brownianPosition, .004, drag);
        brownianVelocity = f(brownianVelocity + acceleration);
        brownianPosition = f(brownianPosition + brownianVelocity);
        brownian = Math.max(0, Math.min(1, brownianPosition));

        // Horizontal input has a Y axis in this camera. Preserve the original
        // small-angle threshold; a single press does not turn the cloud.
        double angle = Math.abs(rotationY);
        rotating = angle >= 1e-5;
        if (rotating) {
            double sine = Math.sin(angle) * (rotationY < 0 ? -1 : 1);
            rotation[0] = rotation[10] = f(Math.cos(angle));
            rotation[2] = f(-sine);
            rotation[8] = f(sine);
        } else {
            rotation[0] = rotation[10] = 1;
            rotation[2] = rotation[8] = 0;
        }
    }

    private static double madd(double a, double b, double c) { return f(f(a) * f(b) + f(c)); }
}
