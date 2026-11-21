# Build IR Studio Cam APK on GitHub

This repository includes `.github/workflows/build-apk.yml`.

## Build automatically
1. Create a GitHub repository.
2. Upload the **contents** of this project folder to the repository root (so `app/`, `gradle/`, `build.gradle.kts`, and `.github/` are at the top level).
3. Commit the files to the `main` branch (or `master`).
4. Open the repository's **Actions** tab and select **Build IR Studio Cam APK**.
5. Wait for the run to finish with a green check mark.
6. Open that workflow run and download the artifact named **IRStudioCam-debug-APK**.
7. Extract the downloaded artifact ZIP. It contains `app-debug.apk`.

## Run it manually
Open **Actions** → **Build IR Studio Cam APK** → **Run workflow** → choose the branch → **Run workflow**.

The workflow builds a debug APK for testing. It uses Java 17, Android SDK Platform 35 / Build Tools 35.0.0, and Gradle 8.9. It invokes the installed Gradle command directly, so the missing `gradle-wrapper.jar` in the supplied project does not block this workflow.

A successful GitHub build only confirms compilation. It does not verify IR waveform compatibility or flash/camera synchronization on the physical Xiaomi phone.
