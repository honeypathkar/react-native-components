module.exports = {
  testEnvironment: 'node',
  // `src` only. `lib` holds the built output of these same files, and Jest
  // would otherwise collect both copies and run every test twice.
  roots: ['<rootDir>/src'],
  moduleFileExtensions: ['ts', 'tsx', 'js'],
};
