// Golden results from the unmodified LG-XMB direction and particle implementations.
// node tests/export-navigation-reference.cjs ../LG-XMB-standby-fix .build/navigation-check/golden.json
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = path.resolve(process.argv[2]);
const Core = require(path.join(source, 'app/ps3-native-core.js'));
const Birth = require(path.join(source, 'app/ps3-particle-birth.js'));
const context = {window: {}, atob: s => Buffer.from(s, 'base64').toString('binary')};
vm.runInNewContext(fs.readFileSync(path.join(source, 'app/ps3-native-data.js'), 'utf8'), context);
const ref = context.window.LGXMBPS3Reference;
const responseCases = [
  {name: 'idle', ticks: 120, events: []},
  {name: 'single right and decay', ticks: 1800, events: [[0, 1]]},
  {name: 'repeat then reverse', ticks: 900, events: Array.from({length: 30}, (_, i) => [i * 3, i < 15 ? 1 : 0])},
  {name: 'reset response', ticks: 150, events: Array.from({length: 12}, (_, i) => [i, 1]), reset: 40}
];
for (const test of responseCases) {
  let response = new Birth.DpadResponse();
  const events = new Map(test.events); test.samples = [];
  for (let tick = 0; tick < test.ticks; tick++) {
    if (tick === test.reset) response = new Birth.DpadResponse();
    const value = response.step(events.has(tick) ? events.get(tick) : 4, 1, 0);
    test.samples.push(value.strength, value.rotation[1], value.brownian);
  }
}
const particleCases = [
  {name: 'enabled idle', frames: 120, seconds: Math.fround(1/60), events: []},
  {name: 'single right', frames: 180, seconds: Math.fround(1/60), events: [[0,1]]},
  {name: 'repeated and reversed', frames: 240, seconds: Math.fround(1/60), events: Array.from({length: 20}, (_, i) => [i * 3, i < 10 ? 1 : 0])},
  {name: 'two updates per frame', frames: 90, seconds: Math.fround(1/30), events: [[0,0],[3,0],[6,0],[9,1],[12,1],[15,1]]},
  {name: 'reset while active', frames: 120, seconds: Math.fround(1/60), events: Array.from({length: 10}, (_, i) => [i, 1]), reset: 12},
  {name: 'disabled while active', frames: 120, seconds: Math.fround(1/60), events: Array.from({length: 10}, (_, i) => [i, 1]), disable: 12}
];
for (const test of particleCases) {
  const wave = new Core.Wave(ref.wave, ref.settings);
  const captured = ref.particles.length / 12, records = new Float32Array(4096 * 12);
  records.set(ref.particles);
  for (let i = captured; i < 4096; i++) {
    records[i * 12 + 3] = Core.DEAD;
    for (let k = 8; k < 12; k++) records[i * 12 + k] = ref.particles[(i % captured) * 12 + k];
  }
  const particles = new Core.Particles(records, new Core.Parameters(ref.particleParams));
  particles.emitter = new Core.Emitter(wave, particles, ref.basis, ref.viewProjection, Birth);
  particles.interaction = new Core.Interaction(particles, Birth);
  const baseline = particles.params.brownian, rotation = particles.params.rotation.slice();
  const events = new Map(test.events);
  for (let frame = 0; frame < test.frames; frame++) {
    if (frame === test.reset || frame === test.disable) {
      particles.params.brownian = baseline; particles.params.rotation.set(rotation);
      particles.interaction = frame === test.disable ? null : new Core.Interaction(particles, Birth);
    }
    if (events.has(frame) && particles.interaction) particles.interaction.direction(events.get(frame));
    wave.advance(test.seconds); particles.advance(test.seconds, true);
  }
  test.count = particles.count;
  test.instances = Array.from(particles.render.subarray(0, particles.count * 8));
}
fs.writeFileSync(process.argv[3], JSON.stringify({responseCases, particleCases}));
console.log('Exported direction and particle navigation reference cases');
