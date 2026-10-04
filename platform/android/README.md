# TrustTunnel Client Android adapter

## Build Instructions

### Prerequisites

- [Python 3](https://www.python.org/downloads/) 3.13 or higher
- [CMake](https://cmake.org/download/) 3.24 or higher
- [Conan](https://github.com/conan-io/conan/releases) 2.0.5 or higher
- [Go](https://go.dev/dl/) 1.18.3 or higher
- [Ninja](https://formulae.brew.sh/formula/ninja) 1.13 or higher
- [Android SDK](https://developer.android.com/tools/releases/platform-tools#downloads) 35.0.0 or higher
- [Android NDK](https://developer.android.com/ndk/downloads) 29.0.14206865 or higher

You can also download SDK and NDK using Android Studio SDK Manager.

### Building

To obtain all required conan dependencies, go to the root of the project and run:

```bash
python3 -m venv env
source env/bin/activate
pip3 install -r scripts/requirements.txt

scripts/bootstrap_conan_deps.py
```

Go to the Android adapter folder, set the CMake version to use and build the adapter:

```bash
cd platform/android

# Use system cmake, ensure `cmake` is in your path
echo cmake.dir=$(dirname $(dirname $(which cmake))) >> local.properties

./gradlew assembleRelease
# or ./gradlew assembleDebug
```

### Building with prebuilt native libraries

Instead of compiling the native code, the build can package already built `libtrusttunnel_android.so` libraries, for example the ones from an official AAR. This only needs JDK 17 and the Android SDK (platform 35 and build tools 35.0.0): no Python, Conan, CMake, Go, Ninja or NDK.

A published AAR is a zip file that contains the libraries as `jni/<abi>/libtrusttunnel_android.so`. GitHub Packages requires authentication even for public packages, so use a classic personal access token with the `read:packages` scope:

```bash
VERSION=1.1.5-rc.6
curl -fL -u "<github-user>:<token>" -o trusttunnel-client-android.aar \
    "https://maven.pkg.github.com/TrustTunnel/TrustTunnelClient/com/adguard/trusttunnel/trusttunnel-client-android/$VERSION/trusttunnel-client-android-$VERSION.aar"
unzip trusttunnel-client-android.aar 'jni/*' -d "$HOME/tt-prebuilt"
```

Then pass the directory that contains the `<abi>` directories as `ttPrebuiltNativeDir`:

```bash
cd platform/android
./gradlew :lib:assembleRelease -PttPrebuiltNativeDir="$HOME/tt-prebuilt/jni" -PttClientVersion=1.1.5-rc.6-split.local
```

The libraries are packaged unchanged and the CMake build is skipped. Nothing native is rebuilt, including the JNI layer, so take the libraries from the same version the Kotlin sources are based on. Compilation, JVM unit tests (`./gradlew :lib:testReleaseUnitTest -PttPrebuiltNativeDir=<dir>`) and lint also work with an empty directory, but packaging the library (an AAR, or an app that includes this project with `includeBuild`) fails unless the directory contains at least one `<abi>/libtrusttunnel_android.so`.

If the AAR cannot be downloaded, take the libraries from an official app APK built against the same library version: they are stored as `lib/<abi>/libtrusttunnel_android.so` (for an app installed from an app bundle, in its ABI split APK, e.g. `split_config.arm64_v8a.apk`). Copy each one to `<dir>/<abi>/libtrusttunnel_android.so`. Otherwise, build them from source as described above.

| Gradle property | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `ttPrebuiltNativeDir` | `TT_PREBUILT_NATIVE_DIR` | Not set: the native code is built with CMake | Directory with `<abi>/libtrusttunnel_android.so` to package instead of building the native code. A relative path is resolved against `platform/android` |
| `ttClientVersion` | `TT_CLIENT_VERSION` | `git describe --tags --match 'v*'`, or `0.0.0-git` | Version of the library |
| `ttPublishRepoUrl` | `TT_PUBLISH_REPO_URL` | `https://maven.pkg.github.com/TrustTunnel/TrustTunnelClient` | Maven repository the library is published to |

## Publishing to a fork's GitHub Packages

By default, the `release` publication goes to the upstream GitHub Packages registry. To publish to a fork's own registry, override the repository URL with `ttPublishRepoUrl`. Credentials are read from the `gpr.user`/`gpr.key` Gradle properties or the `USERNAME`/`TOKEN` environment variables, and the token needs the `write:packages` scope:

```bash
cd platform/android
USERNAME=<github-user> TOKEN=<token> ./gradlew :lib:publishReleasePublicationToGitHubPackagesRepository \
    -PttPrebuiltNativeDir="$HOME/tt-prebuilt/jni" \
    -PttClientVersion=1.1.5-rc.6-split.1 \
    -PttPublishRepoUrl=https://maven.pkg.github.com/<owner>/TrustTunnelClient
```

GitHub Packages does not allow overwriting a published version, so every upload needs a new version.

The fork workflow `.github/workflows/fork-android-lib.yml` does the same on GitHub Actions, with the native libraries of the official AAR:

- Every push to the `split-tunnel` branch runs the unit tests and uploads the AAR as a workflow artifact (version `<base>-split.dev<run number>`) without publishing it.
- Pushing a tag `android-lib/v<base>-split.<n>`, e.g. `android-lib/v1.1.5-rc.6-split.1`, also publishes version `<base>-split.<n>` to the fork's GitHub Packages with `GITHUB_TOKEN`. A manual run with the `publish_version` input does the same. GitHub only offers the *Run workflow* button for workflows on the default branch, so either make `split-tunnel` the fork's default branch or start the run with `gh workflow run fork-android-lib.yml --ref split-tunnel -f publish_version=<base>-split.<n>` after a push has run the workflow once. A version that already exists is never re-published.
- `<base>` is the upstream release the branch is based on (`DEFAULT_BASE_VERSION` in the workflow, or the `base_version` input of a manual run). Its official AAR provides the native libraries.
- The repository secret `GPR_KEY` must contain a classic personal access token with the `read:packages` scope, which is needed to download the official AAR.

Projects that depend on the published library also need a token with the `read:packages` scope to download it, even when the package is public.
