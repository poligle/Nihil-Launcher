{
  description = "NIHIL for Android: a monochrome, dot-matrix launcher";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # Exactly what app/build.gradle.kts asks for, so Gradle never tries to
      # install anything into the read-only store.
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ pkgs.jdk17 android.androidsdk ];

        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        JAVA_HOME = pkgs.jdk17.home;
        # Gradle's own aapt2 is a generic Linux binary that NixOS can't run;
        # use the SDK's, which nixpkgs has patched.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/35.0.0/aapt2";

        shellHook = ''
          export PATH="${sdk}/platform-tools:$PATH"
          echo "NIHIL for Android · ./gradlew installDebug  (phone with USB or wireless debugging)"
        '';
      };
    };
}
