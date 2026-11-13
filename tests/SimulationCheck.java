package org.lgxmb.wallpaper;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Numerical regression against snapshots produced by the original JS simulation. */
public final class SimulationCheck {
    public static void main(String[] args) throws Exception {
        JSONObject reference=new JSONObject(Files.readString(Path.of(args[0])));
        JSONArray cases=new JSONArray(Files.readString(Path.of(args[1])));
        for (int c=0;c<cases.length();c++) {
            JSONObject test=cases.getJSONObject(c);
            JSONObject ref=new JSONObject(reference.toString());
            if (test.has("program")) ref.getJSONObject("wave").put("program",test.getInt("program"));
            if (test.has("clock")) ref.getJSONObject("wave").put("clock",test.getDouble("clock"));
            WaveSimulation wave=new WaveSimulation(ref);
            ParticleSimulation particles=new ParticleSimulation(ref,wave);particles.limit=4096;
            for (int i=0;i<test.getInt("frames");i++) {
                float seconds=(float)test.getDouble("seconds");wave.advance(seconds);particles.advance(seconds);
            }
            double error=compare(wave.controls,wave.controls.length,test.getJSONArray("controls"),0.000001,"wave");
            if (particles.instanceCount!=test.getInt("particleCount")) throw new AssertionError("Particle count differs in "+test.getString("name")+": "+particles.instanceCount+" != "+test.getInt("particleCount"));
            double particleError=compare(particles.instances,particles.instanceCount*8,test.getJSONArray("instances"),0.00001,"particles");
            System.out.println(test.getString("name")+": wave error "+error+", particle error "+particleError+", count "+particles.instanceCount);
        }
        System.out.println("Simulation parity passed");
    }
    private static double compare(float[] actual,int count,JSONArray expected,double tolerance,String label) throws Exception {
        if (count!=expected.length()) throw new AssertionError(label+" length differs");
        double max=0;
        for (int i=0;i<count;i++) {
            double error=Math.abs(actual[i]-expected.getDouble(i));max=Math.max(error,max);
            if (!Float.isFinite(actual[i])||error>tolerance) throw new AssertionError(label+" differs at "+i+": "+actual[i]+" != "+expected.getDouble(i)+" error "+error);
        }
        return max;
    }
}
