// Autolinking: both platforms are plain native modules, so the defaults are
// right — this file only exists to name the Android package explicitly, which
// keeps autolinking working on RN versions that cannot infer it from Kotlin.
module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.liveupdate.LiveUpdatePackage;',
        packageInstance: 'new LiveUpdatePackage()',
      },
      ios: {},
    },
  },
};
