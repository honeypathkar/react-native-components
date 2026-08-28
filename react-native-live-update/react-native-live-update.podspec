require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  # Unscoped on purpose. The npm package is @honeypathkar/react-native-live-update,
  # but a CocoaPods name cannot contain "@" or "/" — so scoped React Native
  # packages keep a plain pod name, and autolinking finds the podspec by
  # scanning the installed package directory rather than by matching names.
  s.name         = "react-native-live-update"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = { "honeypathkar" => "https://github.com/honeypathkar" }

  # ActivityKit is 16.1. The pod itself builds against 16.0 so it can be dropped
  # into an app with a lower floor - every ActivityKit call is behind an
  # @available check and simply reports unsupported below 16.1.
  s.platforms    = { :ios => "16.0" }

  # `repository` is an object, not a string, because the package lives in a
  # subdirectory of a multi-package repo and npm needs `directory` to link to
  # it. Reading ["url"] and stripping npm's "git+" prefix keeps this working —
  # interpolating the object itself yields a Ruby hash in the URL.
  s.source       = {
    :git => package["repository"]["url"].sub(/\Agit\+/, ""),
    :tag => "v#{s.version}",
  }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.frameworks   = "ActivityKit", "SwiftUI", "WidgetKit"

  install_modules_dependencies(s)
end
