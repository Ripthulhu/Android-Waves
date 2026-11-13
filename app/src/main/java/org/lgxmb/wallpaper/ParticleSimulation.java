/* lg-xmb, 2026. SPDX-License-Identifier: GPL-3.0-or-later
 * Particle worker port from LG-XMB ps3-native-core.js. The recovered host birth
 * equations in ps3-particle-birth.js are MIT licensed. See THIRD_PARTY_NOTICES.
 */
package org.lgxmb.wallpaper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import static org.lgxmb.wallpaper.WaveSimulation.f;
import static org.lgxmb.wallpaper.WaveSimulation.value;
import static org.lgxmb.wallpaper.WaveSimulation.bits;

/** Recovered particle motion, sheet emitter and optional horizontal navigation response. */
public final class ParticleSimulation {
    public static final int CAPACITY = 4096;
    public final float[] instances = new float[CAPACITY * 8];
    // Render-only anchors: portrait presentation must not alter worker physics.
    public final float[] birthHeights = new float[CAPACITY];
    private final float[] birthY = new float[CAPACITY];
    public int instanceCount, revision, limit = 2000;
    private static final double DEAD = -666, AGE_LIMIT = (float)0.9999899864196777;
    private final double[] state = new double[CAPACITY * 12];
    private final double[] force, drag, minimum, maximum, pivot, rotation, extra, dt, inverse = new double[3];
    private final double[] worldToField, fieldToWorld;
    private final byte[] field = new byte[1536];
    private final double fieldGain, brownian, angularRate;
    private final boolean hasField, rotate;
    private final double[] temp = new double[4], fieldSample = new double[4], rotated = new double[4], vector = new double[4];
    private final int[] rng = new int[3], free = new int[CAPACITY];
    private int freeCount;
    private double fraction;
    private final WaveSimulation wave;
    private final double[] basis, toWorld;
    private final float[] clip = new float[4];
    private final double[] sampled = new double[3], movement = new double[3], normal = new double[3];
    private final double[] tangent = new double[3], cross = new double[3], direction = new double[3];
    // Up to 60 updates per accepted second, each yielding 16 batch + 1 trail point.
    private int[] previousXY = new int[1020 * 2], nextXY = new int[1020 * 2];
    private double[] previousPositions = new double[1020 * 3], nextPositions = new double[1020 * 3];
    private int previousCount, nextCount, randomCounter, emitterTick;
    private int trailRemaining = 112, trailX = 72, trailY;
    private int waveRevision = -1;
    private final NavigationResponse navigation = new NavigationResponse();
    private boolean navigationEnabled;
    private int pendingNavigation = 4;
    private float phoneAgitation;

    public ParticleSimulation(JSONObject reference, WaveSimulation wave) throws JSONException {
        this.wave = wave;
        JSONArray records = reference.getJSONArray("particles");
        if (records.length() == 0 || records.length()%12 != 0 || records.length() > state.length)
            throw new JSONException("Invalid particle records");
        double[] seed = WaveSimulation.array(records, records.length());
        System.arraycopy(seed,0,state,0,seed.length);
        int captured = seed.length/12;
        // Captured particles have no recovered birth history. Anchor them at
        // their initial positions, so the portrait mapping adds no initial jump.
        for (int i=0;i<captured;i++) birthY[i]=(float)state[i*12+1];
        for (int i=captured;i<CAPACITY;i++) {
            state[i*12+3]=DEAD;
            System.arraycopy(seed,(i%captured)*12+8,state,i*12+8,4);
        }
        for (int i=0;i<CAPACITY;i++) if (state[i*12+3]==DEAD) free[freeCount++]=i;
        JSONArray raw = reference.getJSONArray("particleParams");
        if (raw.length()!=2304) throw new JSONException("Invalid particle parameter block");
        byte[] bytes = new byte[raw.length()];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)raw.getInt(i);
        ByteBuffer params=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (params.getInt(0x784)!=32 || params.getInt(0x788)!=16) throw new JSONException("Invalid particle field dimensions");
        force=read(params,0,3);drag=read(params,16,3);
        System.arraycopy(bytes,128,field,0,field.length);
        fieldToWorld=read(params,0x700,16);worldToField=read(params,0x740,16);
        minimum=read(params,0x800,3);maximum=read(params,0x810,3);pivot=read(params,0x830,3);
        rotation=read(params,0x840,16);extra=read(params,0x890,3);
        fieldGain=params.getFloat(0x8a0);brownian=params.getFloat(0x8a4);angularRate=params.getFloat(0x8ac);
        dt=read(params,0x8b0,3);
        for (int k=0;k<3;k++) {
            if (!(dt[k]>0) || minimum[k]>maximum[k]) throw new JSONException("Invalid particle bounds or timestep");
            inverse[k]=WaveSimulation.reciprocal(dt[k]);
        }
        boolean fieldActive=false, rotationActive=pivot[0]!=0||pivot[1]!=0||pivot[2]!=0;
        for (byte b:field) if (b!=0) fieldActive=true;
        for (int i=0;i<16;i++) if (rotation[i]!=(i%5==0?1:0)) rotationActive=true;
        hasField=fieldGain!=0&&fieldActive;rotate=rotationActive;
        basis=WaveSimulation.array(reference.getJSONArray("basis"),512);
        double[] view=WaveSimulation.array(reference.getJSONArray("viewProjection"),16), transposed=new double[16];
        for (int r=0;r<4;r++) for (int q=0;q<4;q++) transposed[r*4+q]=view[q*4+r];
        toWorld=invert4(transposed);
        pack();
    }
    private static double[] read(ByteBuffer buffer,int offset,int count) {
        double[] values=new double[count];
        for (int i=0;i<count;i++) values[i]=buffer.getFloat(offset+i*4);
        return values;
    }
    private static void transform(double x,double y,double z,double w,double[] matrix,double[] output) {
        for (int k=0;k<4;k++) {
            double a=f(y*matrix[4+k]);a=f(x*matrix[k]+a);a=f(z*matrix[8+k]+a);output[k]=f(w*matrix[12+k]+a);
        }
    }
    private void sampleField(double px,double py,double pz) {
        transform(px,py,pz,1,worldToField,temp);
        double u=f(temp[0]*32-0.5),w=f(temp[1]*16-0.5);
        int x=(int)Math.floor(u),y=(int)Math.floor(w);
        double fx=f(u-x),fy=f(w-y),scale=f(1.0/127);
        int x0=Math.max(0,Math.min(31,x)),x1=Math.max(0,Math.min(31,x+1));
        int y0=Math.max(0,Math.min(15,y)),y1=Math.max(0,Math.min(15,y+1));
        for (int k=0;k<3;k++) {
            double a=f(field[(y0*32+x0)*3+k]*scale),b=f(field[(y0*32+x1)*3+k]*scale);
            double c=f(field[(y1*32+x0)*3+k]*scale),d=f(field[(y1*32+x1)*3+k]*scale);
            double lo=f(f(b-a)*fx+a),hi=f(f(d-c)*fx+c);vector[k]=f(f(hi-lo)*fy+lo);
        }
        transform(vector[0],vector[1],vector[2],0,fieldToWorld,fieldSample);
    }
    private void update() {
        double step=f(angularRate*value(0x3c888888));
        double currentBrownian = navigationEnabled ? f(brownian + f(f(.6000000238418579) * navigation.brownian)) : brownian;
        if (phoneAgitation > 0) currentBrownian = f(currentBrownian + phoneAgitation);
        boolean navigationRotation = navigationEnabled && navigation.rotating;
        boolean currentRotate = navigationRotation || rotate;
        double[] currentRotation = navigationRotation ? navigation.rotation : rotation;
        rng[0]=0x98756161;rng[1]=0x21324889;rng[2]=0x82181158;
        for (int i=0;i<CAPACITY;i++) {
            int at=i*12;
            if (state[at+3]==DEAD) continue;
            if (hasField) sampleField(state[at],state[at+1],state[at+2]);
            if (currentRotate) transform(f(state[at]-pivot[0]),f(state[at+1]-pivot[1]),f(state[at+2]-pivot[2]),0,currentRotation,rotated);
            for (int k=0;k<3;k++) {
                rng[k]*=16807;
                double random=f(value((rng[k]>>>9)|0x40000000)-3);
                double acceleration=f(random*currentBrownian+extra[k]);
                acceleration=f(f(fieldSample[k]*fieldGain+acceleration)+force[k]);
                double position=currentRotate?f(rotated[k]+pivot[k]):state[at+k];
                double velocity=f(f(position-state[at+k])*inverse[k]+state[at+4+k]);
                acceleration=f(-velocity*drag[k]+acceleration);
                velocity=f(acceleration*dt[k]+velocity);
                state[at+4+k]=velocity;state[at+k]=f(velocity*dt[k]+state[at+k]);
            }
            double age=state[at+3]=f(state[at+3]+state[at+7]);
            double phase=f(age*f(2*Math.PI));
            double ox=f(Math.sin(f(phase*f(0.37)))),oy=f(Math.cos(f(phase*f(0.17)))),oz=f(Math.cos(f(phase*f(0.31))));
            double x=state[at+8],y=state[at+9],z=state[at+10],w=state[at+11];
            vector[0]=f(f(w*ox)+f(f(oy*z)-f(oz*y)));
            vector[1]=f(f(w*oy)+f(f(oz*x)-f(ox*z)));
            vector[2]=f(f(w*oz)+f(f(ox*y)-f(oy*x)));
            vector[3]=-f(f(f(ox*x)+f(oy*y))+f(oz*z));
            double length=0;
            for (int k=0;k<4;k++) { vector[k]=f(f(0.5*vector[k])*step+state[at+8+k]);length=f(length+f(vector[k]*vector[k])); }
            length=f(Math.sqrt(length));
            for (int k=0;k<4;k++) state[at+8+k]=f(vector[k]/length);
            if (age>=AGE_LIMIT||state[at]<minimum[0]||state[at]>maximum[0]||state[at+1]<minimum[1]||state[at+1]>maximum[1]||state[at+2]<minimum[2]||state[at+2]>maximum[2]) {
                state[at+3]=DEAD;free[freeCount++]=i;
            }
        }
        revision++;
    }
    private static double fromHalf(int h) {
        int e=(h>>>10)&31;
        return value(((h&0x8000)<<16)|(e!=0?(e==31?0x7f800000:(e+112)<<23):0)|((h&1023)<<13));
    }
    private static int halfTruncate(double x) {
        int u=bits(x),e=(u>>>23)&255;
        return ((u>>>16)&0x8000)|(e>142?0x7c00:e>112?((e-112)<<10)|((u>>>13)&1023):0);
    }
    public int pack() {
        int count=0;
        for (int i=0;i<CAPACITY;i++) {
            int at=i*12;if (state[at+3]==DEAD) continue;
            birthHeights[count]=birthY[i];
            int out=count++*8;double age=state[at+3];
            for (int k=0;k<3;k++) instances[out+k]=(float)state[at+k];
            instances[out+3]=(float)f(f(Math.min(age,f(0.02))*50)*f(Math.max(f(age-f(0.94)),0)*f(-16.666664123535156)+1));
            for (int k=0;k<4;k++) instances[out+4+k]=(float)fromHalf(halfTruncate(state[at+8+k]));
        }
        instanceCount=count;return count;
    }
    public void advance(float seconds) {
        if (!Float.isFinite(seconds)||seconds<0||seconds>1) throw new IllegalArgumentException("Particle interval outside 0..1 seconds");
        fraction+=seconds*60.0;int updates=0;
        while (fraction>=1-1e-9) {
            if (navigationEnabled) {
                navigation.step(pendingNavigation);
                pendingNavigation = 4;
            }
            update();updates++;fraction=Math.max(0,fraction-1);
        }
        emit(updates);pack();
    }
    public void setNavigationEnabled(boolean enabled) {
        if (navigationEnabled == enabled) return;
        navigationEnabled = enabled;
        resetNavigation();
    }
    /** Accept one navigation event, rather than one impulse per render frame. */
    public void navigate(int direction) {
        if (navigationEnabled && (direction == 0 || direction == 1)) pendingNavigation = direction;
    }
    public void resetNavigation() {
        pendingNavigation = 4;
        navigation.reset();
    }
    public void setPhoneAgitation(float amount) {
        phoneAgitation = Float.isFinite(amount) ? Math.max(0, Math.min(.6f, amount)) : 0;
    }
    private double random() {
        int n=++randomCounter;n^=n<<13;
        int bits=(n*(n*n*15731+789221)+1376312589)&0x7fffffff;
        return madd(f(bits),-Math.pow(2,-30),1);
    }
    private static double madd(double a,double b,double c) { return f(f(a)*f(b)+f(c)); }
    private static double unit(double value) { return f(f(f(value)+1)*0.5); }
    private static double dot(double[] a,double[] b) { return f(f(f(a[0]*b[0])+f(a[1]*b[1]))+f(a[2]*b[2])); }
    private void sample(int x,int y) {
        wave.meshPoint(x,y,basis,clip);
        for (int i=0;i<3;i++) sampled[i]=f(clip[0]*toWorld[i*4]+clip[1]*toWorld[i*4+1]+clip[2]*toWorld[i*4+2]+clip[3]*toWorld[i*4+3]);
    }
    private void candidate(int x,int y) {
        sample(x,y);int n=nextCount++;
        nextXY[n*2]=x;nextXY[n*2+1]=y;System.arraycopy(sampled,0,nextPositions,n*3,3);
    }
    private void emit(int updates) {
        if (updates<=0||wave.revision==waveRevision) return;
        waveRevision=wave.revision;nextCount=0;
        for (int n=0;n<updates;n++) {
            if (madd(random(),0.5,0.5)<f(0.479115)) for (int i=0;i<16;i++) {
                int x=(int)f(unit(random())*127),y=(int)f(unit(random())*127);candidate(x,y);
            }
            if (trailRemaining==0) {
                if (madd(random(),0.5,0.5)<f(0.06)) {
                    trailX=(int)f(unit(random())*127);trailY=(int)f(unit(random())*127);
                    trailRemaining=100+(int)f(f(random())*80);
                }
            } else if ((emitterTick&1)==0) {
                if (trailX>=128) trailRemaining=0;
                else {candidate(trailX,trailY);trailX++;trailRemaining--;}
            }
            emitterTick++;
        }
        for (int i=0;i<previousCount;i++) {
            sample(previousXY[i*2],previousXY[i*2+1]);
            birth(i*3,updates);
        }
        int[] xy=previousXY;previousXY=nextXY;nextXY=xy;
        double[] positions=previousPositions;previousPositions=nextPositions;nextPositions=positions;
        previousCount=nextCount;
    }
    private void birth(int old,int updates) {
        double inverseTime=f(1/f(0.0088883*updates));
        for (int k=0;k<3;k++) movement[k]=f(f(sampled[k]-previousPositions[old+k])*inverseTime);
        double speed=f(Math.sqrt(dot(movement,movement)));
        if (speed<f(0.0001)) return;
        for (int k=0;k<3;k++) normal[k]=f(movement[k]*f(1/speed));
        if (unit(random())<f(0.173899)) for (int k=0;k<3;k++) normal[k]=-normal[k];
        double a=f(f(normal[2]*normal[2])+f(normal[1]*normal[1]));
        double b=f(f(normal[2]*normal[2])+f(normal[0]*normal[0]));
        double c=f(f(normal[1]*normal[1])+f(normal[0]*normal[0]));
        int pick=a>b?(a>c?0:2):(b>c?1:2);
        if (pick==0) {tangent[0]=0;tangent[1]=normal[2];tangent[2]=-normal[1];}
        else if (pick==1) {tangent[0]=-normal[2];tangent[1]=0;tangent[2]=normal[0];}
        else {tangent[0]=normal[1];tangent[1]=-normal[0];tangent[2]=0;}
        double inv=f(1/Math.sqrt(pick==0?a:pick==1?b:c));
        for (int k=0;k<3;k++) tangent[k]=f(tangent[k]*inv);
        double theta=f(unit(random())*f(f(51.8695)*f(Math.PI/180))),phi=f(unit(random())*f(2*Math.PI));
        double st=f(Math.sin(theta)),ct=f(Math.cos(theta)),sp=f(Math.sin(phi)),cp=f(Math.cos(phi));
        cross[0]=f(f(normal[1]*tangent[2])-f(normal[2]*tangent[1]));
        cross[1]=f(f(normal[2]*tangent[0])-f(normal[0]*tangent[2]));
        cross[2]=f(f(normal[0]*tangent[1])-f(normal[1]*tangent[0]));
        for (int k=0;k<3;k++) direction[k]=f(f(f(normal[k]*ct)+f(tangent[k]*f(st*cp)))+f(cross[k]*f(st*sp)));
        direction[2]=0; // Recovered depthScale=0, with a Z-axis depth projection.
        double variance=madd(random(),0.282567,1);
        double launchSpeed=f(Math.max(f(0.15064),f(Math.sqrt(f(speed*f(f(0.19)*variance))))));
        double ageIncrement=f(Math.max(f(0.001),f(f(0.00285223)*madd(random(),0.493003,1))));
        if (freeCount==0 || CAPACITY-freeCount>=Math.max(0,Math.min(CAPACITY,limit))) return;
        int slot=free[--freeCount], at=slot*12;
        birthY[slot]=(float)sampled[1];
        Arrays.fill(state,at,at+8,0);
        for (int k=0;k<3;k++) {state[at+k]=sampled[k];state[at+4+k]=f(direction[k]*launchSpeed);}
        state[at+7]=ageIncrement;
    }
    private static double[] invert4(double[] matrix) {
        double[] work=new double[32];
        for (int i=0;i<4;i++) {System.arraycopy(matrix,i*4,work,i*8,4);work[i*8+4+i]=1;}
        for (int i=0;i<4;i++) {
            int pivot=i;for (int j=i+1;j<4;j++) if (Math.abs(work[j*8+i])>Math.abs(work[pivot*8+i])) pivot=j;
            if (Math.abs(work[pivot*8+i])<1e-12) throw new IllegalArgumentException("Singular particle view projection");
            for (int k=0;k<8;k++) {double t=work[i*8+k];work[i*8+k]=work[pivot*8+k];work[pivot*8+k]=t;}
            double scale=1/work[i*8+i];for (int k=0;k<8;k++) work[i*8+k]*=scale;
            for (int j=0;j<4;j++) if (j!=i) {double factor=work[j*8+i];for (int k=0;k<8;k++) work[j*8+k]-=factor*work[i*8+k];}
        }
        double[] out=new double[16];for (int i=0;i<4;i++) for (int j=0;j<4;j++) out[i*4+j]=f(work[i*8+4+j]);return out;
    }
}
