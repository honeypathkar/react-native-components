require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-speech-to-text"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"] || "https://github.com/honeypathkar/react-native-speech-to-text"
  s.license      = package["license"]
  s.authors      = package["author"]
  s.platforms    = { :ios => "13.0" }
  s.source       = { :git => "https://github.com/honeypathkar/react-native-speech-to-text.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.requires_arc = true
  s.swift_version = "5.0"

  s.frameworks = "Speech", "AVFoundation"

  s.dependency "React-Core"

  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES"
  }
end
