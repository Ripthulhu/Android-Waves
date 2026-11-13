// Run against the unmodified LG-XMB source checkout; output stays under .build.
// node tests/export-simulation-reference.cjs ../LG-XMB-standby-fix .build/simulation-check/golden.json
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=path.resolve(process.argv[2]);
const Core=require(path.join(source,'app/ps3-native-core.js'));
const Birth=require(path.join(source,'app/ps3-particle-birth.js'));
const context={window:{},atob:s=>Buffer.from(s,'base64').toString('binary')};
vm.runInNewContext(fs.readFileSync(path.join(source,'app/ps3-native-data.js'),'utf8'),context);
const ref=context.window.LGXMBPS3Reference;
const cases=[
  {name:'initial',frames:0,seconds:Math.fround(1/60)},
  {name:'60 fps',frames:120,seconds:Math.fround(1/60)},
  {name:'30 fps',frames:300,seconds:Math.fround(1/30)},
  {name:'slow frames',frames:60,seconds:Math.fround(0.1)},
  {name:'clock wrap',frames:120,seconds:Math.fround(1/60),clock:9.9999},
  {name:'deformation 0',frames:30,seconds:Math.fround(1/30),program:0},
  {name:'deformation 2',frames:30,seconds:Math.fround(1/30),program:2},
  {name:'deformation 3',frames:30,seconds:Math.fround(1/30),program:3}
];
for(const test of cases) {
  const seed={...ref.wave};
  if(test.clock!==undefined) seed.clock=test.clock;
  if(test.program!==undefined) seed.program=test.program;
  const wave=new Core.Wave(seed,ref.settings);
  const captured=ref.particles.length/12,records=new Float32Array(4096*12);
  records.set(ref.particles);
  for(let i=captured;i<4096;i++) {
    records[i*12+3]=Core.DEAD;
    for(let k=8;k<12;k++) records[i*12+k]=ref.particles[(i%captured)*12+k];
  }
  const particles=new Core.Particles(records,new Core.Parameters(ref.particleParams));
  particles.emitter=new Core.Emitter(wave,particles,ref.basis,ref.viewProjection,Birth);
  for(let i=0;i<test.frames;i++){wave.advance(test.seconds);particles.advance(test.seconds,true);}
  test.controls=Array.from(wave.controls);
  test.particleCount=particles.count;
  test.instances=Array.from(particles.render.subarray(0,particles.count*8));
}
fs.writeFileSync(process.argv[3],JSON.stringify(cases));
console.log('Exported '+cases.length+' original simulation snapshots');
