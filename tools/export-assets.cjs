// SPDX-License-Identifier: GPL-3.0-or-later
// Run once against an LG-XMB checkout to refresh the bundled renderer assets.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const source = path.resolve(process.argv[2] || '../LG-XMB');
const target = path.resolve(__dirname, '../app/src/main/assets');
const context = {
  Uint8Array, Float32Array, Uint32Array, ArrayBuffer, Math,
  atob: text => Buffer.from(text, 'base64').toString('binary')
};
context.window = context;
context.globalThis = context;
vm.createContext(context);
const inputs = ['app/ps3-native-data.js', 'app/ps3-background-data.js'];
for (const file of inputs) {
  vm.runInContext(fs.readFileSync(path.join(source, file), 'utf8'), context);
}
inputs.push('app/ps3-native-core.js', 'app/ps3-particle-birth.js');
fs.mkdirSync(path.join(target, 'shaders'), { recursive: true });
fs.mkdirSync(path.join(target, 'licenses'), { recursive: true });
fs.writeFileSync(path.join(target, 'reference.json'), JSON.stringify(
  context.LGXMBPS3Reference, (_, value) => ArrayBuffer.isView(value) ? Array.from(value) : value
));
const monthly = context.LGXMBPS3MonthlyTextures;
if (monthly.width !== 64 || monthly.height !== 32 || monthly.layers !== 24 || monthly.rgba.length !== 196608) {
  throw new Error('Unexpected monthly texture dimensions');
}
fs.writeFileSync(path.join(target, 'monthly.rgba'), Buffer.from(monthly.rgba));
for (const name of fs.readdirSync(path.join(source, 'shaders'))) {
  if (!/\.(vert|frag)$/.test(name)) continue;
  inputs.push('shaders/' + name);
  let shader = fs.readFileSync(path.join(source, 'shaders', name), 'utf8');
  if (name === 'waveVertex.vert') {
    // Move the Android camera without changing the recovered lighting normals.
    for (const [before, after] of [
      ['uniform float uAspectCorrection;', 'uniform float uAspectCorrection;\n// Android wallpaper camera: move only the projection, preserving optical normals.\nuniform float uPan;\nuniform float uLift;'],
      ['  gl_Position=vec4(p.x*uAspectCorrection,p.yzw);', '  gl_Position=vec4(p.x*uAspectCorrection,p.yzw);\n  gl_Position.x+=uPan*p.w;\n  gl_Position.y+=uLift*p.w;']
    ]) {
      if (shader.split(before).length !== 2) throw new Error('Wave camera adaptation needs updating: ' + before);
      shader = shader.replace(before, after);
    }
  }
  if (name === 'particleVertex.vert' || name === 'glareVertex.vert') {
    for (const [before, after] of [
      ['layout(location=2) in vec2 aCorner;', 'layout(location=2) in vec2 aCorner;\n// Android portrait framing: widen travel after birth, preserving wave attachment.\nlayout(location=3) in float aBirthY;\nuniform float uPortraitSpread;'],
      ['  gl_Position=o0;', '  o0.y+=(aParticle.y-aBirthY)*uPortraitSpread*uModelviewProjection[1].y;\n  gl_Position=o0;']
    ]) {
      if (shader.split(before).length !== 2) throw new Error('Particle portrait adaptation needs updating: ' + before);
      shader = shader.replace(before, after);
    }
  }
  fs.writeFileSync(path.join(target, 'shaders', name), shader);
}
for (const file of ['LICENSE', 'WAVE-PROVENANCE.md', 'docs/WEBGL2-NOTICES.md',
  'app/licenses/PARTICLE-BIRTH-MIT.txt', 'app/licenses/THREE-FXAA-MIT.txt']) {
  fs.copyFileSync(path.join(source, file), path.join(target, 'licenses', path.basename(file)));
}
fs.copyFileSync(path.join(source, 'LICENSE'), path.resolve(__dirname, '../LICENSE'));
fs.writeFileSync(path.join(target, 'source-hashes.json'), JSON.stringify(Object.fromEntries(
  inputs.map(file => [file, crypto.createHash('sha256').update(fs.readFileSync(path.join(source, file))).digest('hex')])
), null, 2) + '\n');
console.log('Exported wave data, monthly textures, shaders and notices.');
