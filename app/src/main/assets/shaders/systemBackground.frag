#version 300 es
// Android system-colour adaptation. LG-XMB, GPL-3.0-or-later.
precision highp float;
uniform vec3 uPalette[3]; // Linear-light primary, secondary and tertiary.
uniform float uDaylight;
in vec2 vUV;
layout(location=0) out vec4 outColor;

void main() {
    // Bottom-up UVs match the original monthly cache. Leave a dark upper field
    // and a broad lower glow so the original wave highlights remain visible.
    float vertical=smoothstep(0.0,1.0,vUV.y);
    vec3 colour=mix(uPalette[0],uPalette[1],vertical);
    vec2 halo=(vUV-vec2(0.78,0.32))*vec2(1.15,1.6);
    float glow=max(0.0,1.0-dot(halo,halo));
    colour=mix(colour,uPalette[2],0.24*glow*glow);
    float lower=1.0-vUV.y;
    float day=mix(0.20,0.70,lower);
    float night=0.006+0.15*lower*lower*lower;
    vec3 linear=colour*mix(night,day,uDaylight);
    // The existing background cache/compositor stores display-space RGB.
    vec3 low=12.92*linear;
    vec3 high=1.055*pow(linear,vec3(1.0/2.4))-0.055;
    outColor=vec4(mix(high,low,lessThanEqual(linear,vec3(0.0031308))),1.0);
}
