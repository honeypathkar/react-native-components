// Test-time only. The published package is built by react-native-builder-bob,
// which brings its own Babel configuration; this exists so Jest can read
// TypeScript sources directly.
module.exports = {
  presets: [
    ['@babel/preset-env', { targets: { node: 'current' } }],
    '@babel/preset-typescript',
  ],
};
