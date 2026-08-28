require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "react-native-predictive-back-gesture"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/honeypathkar/react-native-predictive-back-gesture"
  s.license      = "MIT"
  s.author       = { package["author"] => package["author"] }
  s.platforms    = { :ios => "12.0" }
  s.source       = { :git => "https://github.com/honeypathkar/react-native-predictive-back-gesture.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.requires_arc = true

  s.dependency "React-Core"
end
