# Template for Casks/kubekubedashdash.rb in beytullah-gunduz/homebrew-tap.
# render-cask.sh fills in the version and checksum; the release workflow
# publishes the result to the tap after every stable tag.
cask "kubekubedashdash" do
  version "@VERSION@"
  sha256 "@SHA256@"

  url "https://github.com/beytullah-gunduz/kubekubedashdash/releases/download/v#{version}/KubeKubeDashDash-#{version}.dmg"
  name "KubeKubeDashDash"
  desc "Desktop Kubernetes dashboard"
  homepage "https://github.com/beytullah-gunduz/kubekubedashdash"

  livecheck do
    url :url
    strategy :github_latest
  end

  # The release DMG is built on an Apple Silicon runner only.
  depends_on arch: :arm64
  depends_on :macos

  app "KubeKubeDashDash.app"

  zap trash: "~/Library/Application Support/KubeKubeDashDash"
end
