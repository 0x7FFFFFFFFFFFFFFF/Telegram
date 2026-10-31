@echo off
rem Builds the Telegram app (TMessagesProj_App, universal "afat" APK with all ABIs)
rem and copies the APK into this folder.
rem
rem Usage: build.cmd [release^|debug] [extra gradle arguments]
rem   release (default)  minified build, saved as Telegram-VERSION.apk
rem   debug              unminified build with debug logs, saved as Telegram-VERSION-debug.apk
rem
rem Both use the package name org.telegram.messenger.beta, so the APK installs next to the official
rem Telegram app: Android only lets an APK signed with the same key replace an installed app, and this
rem one is signed with the repository's test key. To build the release APK with the official package
rem name instead (it then installs only where official Telegram isn't installed), add
rem -PAPP_PACKAGE=org.telegram.messenger
rem
rem Needs: JDK 17+ (JAVA_HOME, Android Studio's JBR or java on PATH), the Android SDK
rem (ANDROID_HOME, local.properties or %LOCALAPPDATA%\Android\Sdk) with an accepted SDK
rem license, and git. Gradle downloads the pinned NDK/build-tools itself if missing.

setlocal EnableExtensions
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
pushd "%ROOT%" || exit /b 1

set "BUILD_TYPE=%~1"
if "%BUILD_TYPE%"=="" set "BUILD_TYPE=release"
rem (the debug build type adds the ".beta" suffix itself, release gets it through APP_PACKAGE)
if /i "%BUILD_TYPE%"=="release" (
    set "VARIANT=AfatRelease"
    set "BUILD_TYPE=release"
    set "PACKAGE_ARG= -PAPP_PACKAGE=org.telegram.messenger.beta"
) else if /i "%BUILD_TYPE%"=="debug" (
    set "VARIANT=AfatDebug"
    set "BUILD_TYPE=debug"
    set "PACKAGE_ARG="
) else (
    echo Usage: build.cmd [release^|debug] [extra gradle arguments]
    goto :fail
)
shift
set "GRADLE_ARGS="
:collect_args
if "%~1"=="" goto :args_done
set "GRADLE_ARGS=%GRADLE_ARGS% %1"
shift
goto :collect_args
:args_done

rem --- Android SDK ---
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME if not exist "local.properties" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME if not exist "local.properties" (
    echo Android SDK not found: set ANDROID_HOME or create local.properties with sdk.dir.
    goto :fail
)

rem --- JDK ---
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo Java not found: install JDK 17+ and set JAVA_HOME.
    goto :fail
)

rem --- Git submodules (native libraries and media3): fetch missing ones, move outdated ones ---
rem --- to the commit this checkout pins (local changes in a submodule are never discarded) ---
if exist ".git" (
    for /f "tokens=2" %%p in ('git submodule status 2^>nul ^| findstr /b /c:"-"') do (
        echo Fetching submodule %%p
        git -c core.longpaths=true submodule update --init --recursive --depth=1 --force -- "%%p"
        if errorlevel 1 goto :submodule_failed
    )
    for /f "tokens=2" %%p in ('git submodule status 2^>nul ^| findstr /b /c:"+"') do (
        echo Updating submodule %%p
        git -c core.longpaths=true submodule update --init --recursive --depth=1 -- "%%p"
        if errorlevel 1 goto :submodule_failed
    )
)

rem --- Build (the repository has no gradlew.bat, so run the wrapper jar directly) ---
rem --- extra arguments come last, so their -PAPP_PACKAGE wins over ours ---
echo Building :TMessagesProj_App:assemble%VARIANT% ...
"%JAVA_EXE%" -Xmx64m -Xms64m "-Dorg.gradle.appname=build.cmd" -classpath "%ROOT%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :TMessagesProj_App:assemble%VARIANT%%PACKAGE_ARG%%GRADLE_ARGS%
if errorlevel 1 (
    echo Build failed.
    goto :fail
)

rem --- Copy the APK here ---
set "APP_VERSION="
for /f "tokens=1,* delims==" %%a in (gradle.properties) do if "%%a"=="APP_VERSION_NAME" set "APP_VERSION=%%b"
set "APK_SRC=TMessagesProj_App\build\outputs\apk\afat\%BUILD_TYPE%\app.apk"
set "APK_DST=Telegram-%APP_VERSION%.apk"
if "%BUILD_TYPE%"=="debug" set "APK_DST=Telegram-%APP_VERSION%-debug.apk"
if not exist "%APK_SRC%" (
    echo Build finished but %APK_SRC% was not found.
    goto :fail
)
copy /y "%APK_SRC%" "%APK_DST%" >nul || goto :fail
echo.
echo APK: %ROOT%\%APK_DST%
popd
endlocal
exit /b 0

:submodule_failed
echo Failed to update the git submodules, run build.cmd again to retry.

:fail
popd
endlocal
exit /b 1
