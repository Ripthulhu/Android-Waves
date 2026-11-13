/* lg-xmb, 2026. SPDX-License-Identifier: GPL-3.0-or-later
 * Native port of LG-XMB's numerical PS3 3.01 wave reconstruction.
 * Source and recovered-data provenance: LG-XMB/WAVE-PROVENANCE.md.
 */
package org.lgxmb.wallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** CPU control lattice; the original GLSL evaluates the visible mesh on the GPU. */
public final class WaveSimulation {
    public final float[] controls = new float[1444];
    public int revision;
    private final double[] p, velocity, previous, matrix;
    private final double[] output = new double[1444], lattice = new double[2156];
    private final double[] transitionLattice = new double[2156], core = new double[512];
    private final double[] weights = new double[12], delta = new double[4];
    private final double[] origin, scale, extent, inverse = new double[4];
    private final double step, rest, tension, perturbation, damping, endY, endZ, start;
    private final int program;
    private double clock, smoothedClock, fraction;
    private int counter, ticks, transitionTicks;
    private float motionTiltX, motionTiltY, motionShake;

    // Double intermediates with explicit float boundaries mirror Math.fround in
    // the reference. Plain float expressions round multiply/add too early.
    static double f(double value) { return (float) value; }
    static double value(int bits) { return Float.intBitsToFloat(bits); }
    static int bits(double value) { return Float.floatToRawIntBits((float) value); }
    private static final int[] RECIP = {
        0x7ffbe0,0x7f87a6,0x70ef72,0x708b40,0x638b12,0x633aea,0x5792c4,0x574aa0,
        0x4cca7e,0x4c9262,0x430a44,0x42d62a,0x3a2e12,0x39fdfa,0x3215e4,0x31f1d2,
        0x2aa9be,0x2a85ac,0x23d59a,0x23bd8e,0x1d8576,0x1d8576,0x17ad5a,0x17ad5a,
        0x124543,0x124543,0x0d392d,0x0d392d,0x08851a,0x08851a,0x041d07,0x041d07
    };
    static double reciprocal(double x) {
        int a = bits(x), e = (a >>> 23) & 255;
        if (e < 1 || e > 252) throw new IllegalArgumentException("Invalid reciprocal input");
        int b = RECIP[(a >>> 18) & 31] | ((253 - e) << 23) | (a & 0x80000000);
        double base = value((b & 0x007ffc00) | 0x3f800000);
        double delta = f(f((b & 1023) * Math.pow(2, -13)) * f((a & 0x7ffff) * Math.pow(2, -19)));
        return value((b & 0xff800000) | (bits(f(base - delta)) & 0x007fffff));
    }
    static double noise(int counter) {
        int n = counter ^ (counter << 13);
        int h = (n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff;
        return f(1 - f(h) * Math.pow(2, -30));
    }
    static double[] array(JSONArray input, int length) throws JSONException {
        if (input.length() != length) throw new JSONException("Unexpected reference array size");
        double[] result = new double[length];
        for (int i = 0; i < length; i++) {
            result[i] = f(input.getDouble(i));
            if (!Double.isFinite(result[i])) throw new JSONException("Non-finite reference value");
        }
        return result;
    }
    private static double[] vector(JSONObject settings, String prefix) throws JSONException {
        return new double[]{f(settings.getDouble(prefix + " X")), f(settings.getDouble(prefix + " Y")),
            f(settings.getDouble(prefix + " Z")), 1};
    }
    public WaveSimulation(JSONObject reference) throws JSONException {
        JSONObject seed = reference.getJSONObject("wave"), settings = reference.getJSONObject("settings");
        p = array(seed.getJSONArray("current"), 1444);
        velocity = array(seed.getJSONArray("velocity"), 1444);
        previous = seed.has("previous") ? array(seed.getJSONArray("previous"), 1444) : p.clone();
        matrix = array(seed.getJSONArray("matrix"), 16);
        clock = f(seed.optDouble("clock", 0));
        smoothedClock = f(seed.optDouble("smoothedClock", 0));
        fraction = f(seed.optDouble("fraction", 0));
        counter = (int) seed.optLong("counter", 0);
        program = seed.optInt("program", settings.getInt("FFD SHADER"));
        step = f(settings.getDouble("TIMESTEP"));
        if (program < 0 || program > 3 || !(step > 0)) throw new JSONException("Invalid wave program or step");
        rest = f(settings.getDouble("LENGTH")); tension = f(settings.getDouble("TENSION"));
        perturbation = f(settings.getDouble("PERTURBATION")); damping = f(settings.getDouble("DAMPING"));
        endY = f(settings.getDouble("END Y")); endZ = f(settings.getDouble("END Z"));
        start = f(settings.getDouble("FFD PARAM 1"));
        origin = vector(settings, "FFD OFFSET"); scale = vector(settings, "FFD SCALE2");
        extent = vector(settings, "FFD SCALE1");
        for (int k = 0; k < 4; k++) inverse[k] = reciprocal(extent[k]);
        interpolate();
        if (seed.has("lattice")) System.arraycopy(array(seed.getJSONArray("lattice"), 2156), 0, lattice, 0, 2156);
        else generateLattice();
        deform();
    }
    private void spring(int a, int b, double length, double strength) {
        double sum = 0;
        for (int k = 0; k < 4; k++) { delta[k] = f(p[b+k] - p[a+k]); sum = f(sum + f(delta[k]*delta[k])); }
        double distance = f(Math.sqrt(sum));
        if (distance == 0) return;
        double amplitude = f(strength * f(distance - length));
        for (int k = 0; k < 4; k++) {
            double impulse = f(amplitude * f(delta[k] / distance));
            velocity[a+k] = f(velocity[a+k] + impulse); velocity[b+k] = f(velocity[b+k] - impulse);
        }
    }
    private void tick() {
        double dt = f(step * f(0.0001));
        System.arraycopy(p, 0, previous, 0, p.length);
        clock = f(clock + dt);
        if (transitionTicks > 0) transitionTicks--;
        if (clock > 10) { clock = 0; System.arraycopy(lattice, 0, transitionLattice, 0, lattice.length); transitionTicks = 60; }
        // Android motion adaptation: gently excite the existing spring sheet.
        // Zero motion takes the original arithmetic path unchanged.
        double noiseGain = motionShake > 0 ? f(perturbation * f(1 + .5 * motionShake)) : perturbation;
        for (int y = 0; y < 19; y++) for (int x = 0; x < 19; x++) {
            int a = (y * 19 + x) * 4;
            if (x > 0) spring(a, a-4, rest, tension);
            if (x > 1) spring(a, a-8, f(2*rest), f(10*tension));
            if (y > 0) spring(a, a-76, rest, tension);
            if (y > 1) spring(a, a-152, f(2*rest), f(10*tension));
            for (int k = 0; k < 3; k++) velocity[a+k] = f(noise(++counter) * noiseGain + velocity[a+k]);
        }
        double damp = f(damping * step);
        for (int i = 0; i < p.length; i++) { p[i] = f(velocity[i] * dt + p[i]); velocity[i] = f(velocity[i] - f(velocity[i]*damp)); }
        smoothedClock = f(f(clock-smoothedClock) * f(0.1) + smoothedClock);
        for (int y = 0; y < 19; y++) {
            int a = y * 76;
            velocity[a] = f(velocity[a] + f(step * f(0.02)));
            double phase = f(f(y / 19.0) + smoothedClock);
            a += 72; p[a] = 0;
            p[a+1] = f(f(f(f(Math.sin(f(phase * 11))) + 1) * f(0.5)) * endY);
            p[a+2] = f(f(f(f(Math.cos(f(phase * 15))) + 1) * f(0.5)) * endZ); p[a+3] = 1;
            if (motionTiltY != 0) p[a+1] = f(p[a+1] + f(endY * .15 * motionTiltY));
            if (motionTiltX != 0) p[a+2] = f(p[a+2] + f(endZ * .15 * motionTiltX));
            for (int k = 0; k < 4; k++) velocity[a+k] = 0;
        }
        ticks++;
    }
    private void interpolate() {
        for (int i = 0; i < p.length; i += 4) {
            for (int k = 0; k < 4; k++) output[i+k] = f(p[i+k]*fraction + f(previous[i+k]*f(1-fraction)));
            double d = f(output[i]-start), phase = d < 0 ? 0 : f(d*f(Math.PI/2));
            double u = d < 0 ? 0 : f(d/5), t = f(1-Math.min(1,Math.max(0,u)));
            double smooth = f(f(t*t)*f(t*-2+3)), factor = f(f(1.3)-f(f(Math.cos(phase))*smooth));
            output[i+1] = f(output[i+1]*factor); output[i+2] = f(output[i+2]*factor);
        }
    }
    private void generateLattice() {
        double time = Math.max(0, f(f(clock-f(step*f(0.0001)))*10));
        for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) {
            int i = ((z*4+y)*8+x)*4;
            switch (program) {
                case 0: ffd0(x/8.0,y/4.0,z/4.0,time,core,i); break;
                case 1: ffd1(x/8.0,y/4.0,z/4.0,time,core,i); break;
                case 2: ffd2(x/8.0,y/4.0,z/4.0,time,core,i); break;
                default: ffd3(x/8.0,y/4.0,z/4.0,time,core,i);
            }
            for (int k = 0; k < 4; k++) core[i+k] = f(core[i+k]*extent[k]+origin[k]);
        }
        for (int z = 0; z < 7; z++) for (int y = 0; y < 7; y++) for (int x = 0; x < 11; x++) {
            int i = ((z*7+y)*11+x)*4;
            int j = ((Math.min(3,Math.max(0,z-1))*4+Math.min(3,Math.max(0,y-1)))*8+Math.min(7,Math.max(0,x-1)))*4;
            System.arraycopy(core,j,lattice,i,4);
        }
        if (transitionTicks > 0) {
            double blend = 1-transitionTicks/60.0; blend = blend*blend*(3-2*blend);
            for (int i = 0; i < lattice.length; i++) lattice[i] = f(transitionLattice[i]+(lattice[i]-transitionLattice[i])*blend);
        }
    }
    private void deform() {
        double sixth = 0.1666666567325592, third = 0.6666666269302368, half = 0.4999999701976776;
        for (int i = 0; i < p.length; i += 4) {
            double nx=(output[i]-origin[0])*inverse[0], ny=(output[i+1]-origin[1])*inverse[1];
            double nz=(output[i+2]-origin[2])*inverse[2], nw=(output[i+3]-origin[3])*inverse[3];
            double xx=Math.max(0,Math.min(0.9990000128746033,nx))*8;
            double yy=Math.max(0,Math.min(0.9990000128746033,ny))*4, zz=Math.max(0,Math.min(0.9990000128746033,nz))*4;
            int ix=(int)Math.floor(xx), iy=(int)Math.floor(yy), iz=(int)Math.floor(zz);
            for (int ax=0; ax<3; ax++) {
                double t=ax==0?xx-ix:ax==1?yy-iy:zz-iz, t2=t*t, t3=t2*t; int q=ax*4;
                weights[q]=-sixth*t3+half*t2-half*t+sixth; weights[q+1]=half*t3-2*half*t2+third;
                weights[q+2]=-half*t3+half*t2+half*t+sixth; weights[q+3]=sixth*t3;
            }
            double dx=0,dy=0,dz=0,dw=0;
            for (int z=0;z<4;z++) for (int y=0;y<4;y++) for (int x=0;x<4;x++) {
                double weight=weights[x]*weights[4+y]*weights[8+z]; int j=(((iz+z)*7+iy+y)*11+ix+x)*4;
                dx+=lattice[j]*weight; dy+=lattice[j+1]*weight; dz+=lattice[j+2]*weight; dw+=lattice[j+3]*weight;
            }
            dx=(nx+dx)*scale[0]; dy=(ny+dy)*scale[1]; dz=(nz+dz)*scale[2]; dw=(nw+dw)*scale[3];
            for (int k=0;k<4;k++) controls[i+k]=(float)(dx*matrix[k]+dy*matrix[4+k]+dz*matrix[8+k]+dw*matrix[12+k]);
        }
        revision++;
    }
    /** Bounded Android adaptation; these are not recovered PS3 sensor bindings. */
    public void setPhoneMotion(float tiltX, float tiltY, float shake) {
        if (!Float.isFinite(tiltX) || !Float.isFinite(tiltY) || !Float.isFinite(shake)) return;
        motionTiltX = Math.max(-1, Math.min(1, tiltX));
        motionTiltY = Math.max(-1, Math.min(1, tiltY));
        motionShake = Math.max(0, Math.min(1, shake));
    }

    /** Advance simulation time, not wall time; callers must exclude hidden intervals. */
    public int advance(float seconds) {
        if (!Float.isFinite(seconds) || seconds<0 || seconds>1) throw new IllegalArgumentException("Wave interval outside 0..1 seconds");
        fraction=f(fraction+f(f(seconds)*60)); int before=ticks;
        while (fraction>1) { tick(); fraction=f(fraction-1); }
        interpolate(); if (ticks!=before) generateLattice(); deform(); return ticks-before;
    }
    public void meshPoint(int x,int y,double[] basis,float[] out) {
        x=((x%128)+128)%128; y=((y%128)+128)%128;
        int px=x>>3, lx=(x&7)*64, py=y>>3, ly=(y&7)*64;
        double a=0,b=0,d=0,e=0;
        for (int v=0;v<4;v++) for (int u=0;u<4;u++) {
            double weight=basis[lx+u]*basis[ly+v]; int j=((py+v)*19+px)*4+u*4;
            a+=controls[j]*weight; b+=controls[j+1]*weight; d+=controls[j+2]*weight; e+=controls[j+3]*weight;
        }
        out[0]=(float)a;out[1]=(float)b;out[2]=(float)d;out[3]=(float)e;
    }

    private static double exp2(double x) { return Math.pow(2,x); }
    private static void ffd0(double x,double y,double z,double time,double[] out,int at) {
    double s0 = f(x);
    double s1 = f(y);
    double s2 = f(-s1 + 0.5);
    double s3 = f(time);
    double s4 = f(s3 + 2400.0);
    double s5 = f(f(s0 * 1.4426950216293335) * f(2));
    double s6 = f(-s0 * 0.800000011920929 + 1.0);
    double s7 = f(exp2(-s5));
    double s8 = f(s4 * 0.10000000149011612);
    double s9 = f(s4 * 0.10000000149011612 + 20.0);
    double s10 = f(Math.sin(s8));
    double s11 = f(f(s4 * s6) * f(0.5));
    double s12 = f(-s0 * 6.28000020980835 + s11);
    double s13 = f(s4 * 0.12999999523162842);
    double s14 = f(s0 * 15.700000762939453 + -s13);
    double s15 = f(Math.sin(s9));
    double s16 = f(s15 + 0.20000000298023224);
    double s17 = f(s14 + -2.0);
    double s18 = f(f(Math.sin(s17)) * f(0.25));
    double s19 = f(-s16 * 0.8333333134651184 + s0);
    double s20 = f(s0);
    double s21 = f(s10 + 0.20000000298023224);
    double s22 = f(-s21 * 0.8333333134651184 + s0);
    double s23 = f(s19 * s19);
    double s24 = f(s23 * -14.426950454711914);
    double s25 = f(s22 * s22);
    double s26 = f(exp2(s24));
    double s27 = f(s0);
    double s28 = f(s25 * -72.13475036621094);
    double s29 = f(s0 * 1.309999942779541);
    double s30 = f(0.20000000298023224);
    double s31 = f(exp2(s28));
    double s32 = f(s26 * -0.15000000596046448 + s31);
    double s33 = f(-s7 + 1.0);
    double s34 = f(s2 * s33);
    double s35 = f(Math.sin(s12));
    double s36 = f(s34 * s35 + s32);
    double s37 = f(s20 * s30 + s29 * s27);
    double s38 = f(0.0);
    double s39 = f(0.0);
    double s40 = f(s18 + s36);
    out[at] = s37;
    out[at + 1] = s40;
    out[at + 2] = s38;
    out[at + 3] = s39;
    }
    private static void ffd1(double x,double y,double z,double time,double[] out,int at) {
    double s0 = f(x);
    double s1 = f(z);
    double s2 = f(s0 + -0.5);
    double s3 = f(time);
    double s4 = f(s3 * 2.0);
    double s5 = f(f(s2 * 1.4426950216293335) * f(2));
    double s6 = f(Math.sin(s4));
    double s7 = f(s0 * 7.850000381469727);
    double s8 = f(-s3 * 2.5 + s7);
    double s9 = f(0.20000000298023224);
    double s10 = f(s6 + 3.0);
    double s11 = f(exp2(s5));
    double s12 = f(s8 + -1.25);
    double s13 = f(s3 * 0.25);
    double s14 = f(f(Math.sin(s13)) * f(0.5));
    double s15 = f(s3 * 0.10000000149011612);
    double s16 = f(s11 + -1.0);
    double s17 = f(s11 + 1.0);
    double s18 = f(Math.sin(s15));
    double s19 = f(s18 + 0.20000000298023224);
    double s20 = f(s16 / s17);
    double s21 = f(s3 * -0.00014426949201151729);
    double s22 = f(-s19 * 0.8333333134651184 + s0);
    double s23 = f(s22 * s22);
    double s24 = f(s0);
    double s25 = f(Math.sin(s12));
    double s26 = f(s20 + 1.0);
    double s27 = f(s21);
    double s28 = f(s26 * s10);
    double s29 = f(exp2(s27));
    double s30 = f(-s1 * 6.28000020980835 + s3);
    double s31 = f(s0);
    double s32 = f(s31 * 1.2999999523162842);
    double s33 = f(s31);
    double s34 = f(s28 * s29);
    double s35 = f(s23 * -72.13475036621094);
    double s36 = f(exp2(s35));
    double s37 = f(s34 * s25);
    double s38 = f(s37 * 0.23999999463558197 + s14);
    double s39 = f(s30);
    double s40 = f(s38 + s36);
    double s41 = f(f(Math.sin(s39)) * f(0.125));
    double s42 = f(s40 + s41);
    double s43 = f(s24 * s9 + s32 * s33);
    double s44 = f(s43 + -0.15000000596046448);
    double s45 = f(0.0);
    double s46 = f(0.0);
    out[at] = s44;
    out[at + 1] = s42;
    out[at + 2] = s45;
    out[at + 3] = s46;
    }
    private static void ffd2(double x,double y,double z,double time,double[] out,int at) {
    double s0 = f(0.20000000298023224);
    double s1 = f(x);
    double s2 = f(time);
    double s3 = f(s1 * 7.850000381469727);
    double s4 = f(s2 * -0.00014426949201151729);
    double s5 = f(-s2 * 2.5 + s3);
    double s6 = f(s2 * 2.0);
    double s7 = f(s1 + -0.5);
    double s8 = f(f(s7 * 1.4426950216293335) * f(2));
    double s9 = f(Math.sin(s6));
    double s10 = f(s1);
    double s11 = f(s9 + 3.0);
    double s12 = f(exp2(s8));
    double s13 = f(s12 + 1.0);
    double s14 = f(s1);
    double s15 = f(s12);
    double s16 = f(s15 + -1.0);
    double s17 = f(s14 * 1.2999999523162842);
    double s18 = f(s16 / s13);
    double s19 = f(s5 + -1.25);
    double s20 = f(s18 + 1.0);
    double s21 = f(exp2(s4));
    double s22 = f(s20 * s11);
    double s23 = f(s22 * s21);
    double s24 = f(Math.sin(s19));
    double s25 = f(s14 * s0 + s17 * s10);
    double s26 = f(s23 * s24);
    double s27 = f(s26 * 0.23999999463558197);
    double s28 = f(0.0);
    double s29 = f(0.0);
    out[at] = s25;
    out[at + 1] = s27;
    out[at + 2] = s28;
    out[at + 3] = s29;
    }
    private static void ffd3(double x,double y,double z,double time,double[] out,int at) {
    double s0 = f(x);
    double s1 = f(y);
    double s2 = f(-s0 * 0.800000011920929 + 1.0);
    double s3 = f(f(s0 * 1.4426950216293335) * f(2));
    double s4 = f(f(-s0 * 6.2831854820251465) * f(2));
    double s5 = f(s2);
    double s6 = f(f(s5 * time + s4) * f(0.5));
    double s7 = f(exp2(-s3));
    double s8 = f(time);
    double s9 = f(-s7 + 1.0);
    double s10 = f(Math.sin(s6));
    double s11 = f(-s1 + 0.5);
    double s12 = f(s8 * 0.10000000149011612 + 20.0);
    double s13 = f(Math.sin(s12));
    double s14 = f(s0 + -s13);
    double s15 = f(s8 * 0.10000000149011612);
    double s16 = f(Math.sin(s15));
    double s17 = f(s14 * s14);
    double s18 = f(s17 * -14.426950454711914);
    double s19 = f(s0 + -s16);
    double s20 = f(s19 * s19);
    double s21 = f(exp2(s18));
    double s22 = f(s20 * -72.13475036621094);
    double s23 = f(s0 * 1.2000000476837158);
    double s24 = f(s23 * s0);
    double s25 = f(exp2(s22));
    double s26 = f(s11 * s9);
    double s27 = f(f(s21 * -0.15000000596046448 + s25) * f(0.5));
    double s28 = f(f(s26 * s10 + s27) * f(2));
    double s29 = f(0.0);
    double s30 = f(0.0);
    out[at] = s24;
    out[at + 1] = s28;
    out[at + 2] = s29;
    out[at + 3] = s30;
    }
}
