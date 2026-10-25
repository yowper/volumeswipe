# -*- coding: utf-8 -*-
# 一键编译 VolumeSwipe：自动下载 JDK / Android SDK / Gradle，然后出 APK
# 双击 build_apk.bat 即可运行本脚本
# 工具链统一放在 D:\apktools（和源码目录分离，用户要求）：
#   D:\apktools\jdk-17          JDK 17
#   D:\apktools\android-sdk     Android SDK（platform-tools / android-34 / build-tools）
#   D:\apktools\gradle-8.7      Gradle
#   D:\apktools\vosk-model-small-cn.zip  语音包原始压缩包
#      （真正打进 APK 的是 app/src/main/assets/model-cn/，那份在源码里不动）

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ROOT

# 开发环境根目录（JDK/SDK/Gradle/语音包压缩包全在这，不进源码目录）
$TOOLS = 'D:\apktools'
New-Item -ItemType Directory -Force -Path $TOOLS | Out-Null

$JDK_URL    = 'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip'
$SDK_URL    = 'https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-11076708_latest.zip'
$GRADLE_URL = 'https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip'

# ---------------- 失败时给出明确位置 ----------------
trap {
    Write-Host ""
    Write-Host "================ 执行失败 ================" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ""
    Write-Host "出错位置：" -ForegroundColor Yellow
    Write-Host $_.InvocationInfo.PositionMessage -ForegroundColor Yellow
    Write-Host ""
    Write-Host "把上面这几行发给我。" -ForegroundColor Yellow
    exit 1
}

# ---------------- 小工具 ----------------
function Remove-Safe($path) {
    if ($path -and (Test-Path -LiteralPath $path)) {
        try {
            Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
        } catch {
        }
    }
}

function Write-Step($msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Get-RemoteFile($url, $out, $minMB = 10) {
    if ((Test-Path -LiteralPath $out) -and ((Get-Item -LiteralPath $out).Length -gt ($minMB * 1MB))) {
        Write-Host "    已下载过，跳过：$out"
        return
    }
    Remove-Safe $out
    Write-Host "    下载: $url"
    & curl.exe -L --fail --retry 2 --retry-delay 2 -o $out $url
    if ($LASTEXITCODE -ne 0) {
        throw "下载失败：$url"
    }
    $size = [math]::Round((Get-Item -LiteralPath $out).Length / 1MB, 1)
    Write-Host "    完成: $size MB"
}

function Expand-ZipTo($zip, $dest) {
    # 只用 .NET 解压。PowerShell 5.1 自带的 Expand-Archive 有 bug：
    # 解压大文件包时会在内部清理阶段报 "Cannot find path ... because it does not exist"。
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    Remove-Safe $dest
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
    }
    catch {
        # 目标目录有残留时重来一次
        Remove-Safe $dest
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
    }
}

Write-Host "=================================================" -ForegroundColor Green
Write-Host "  VolumeSwipe 一键编译" -ForegroundColor Green
Write-Host "  工程目录: $ROOT" -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green

# ---------------- 1. JDK ----------------
Write-Step "检查 JDK 17"
$jdkDir = Join-Path $TOOLS 'jdk-17'
if (-not (Test-Path -LiteralPath (Join-Path $jdkDir 'bin\java.exe'))) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $jdkDir = $env:JAVA_HOME
        Write-Host "    使用已有 JAVA_HOME: $jdkDir"
    }
    else {
        $zip = Join-Path $TOOLS 'jdk17.zip'
        Get-RemoteFile $JDK_URL $zip 100
        Remove-Safe $jdkDir
        Expand-ZipTo $zip $TOOLS
        $found = Get-ChildItem -LiteralPath $TOOLS -Directory |
                 Where-Object { $_.Name -like 'jdk-17*' -and $_.Name -ne 'jdk-17' } |
                 Select-Object -First 1
        if ($found) {
            Move-Item -LiteralPath $found.FullName -Destination $jdkDir -Force
        }
        Remove-Safe $zip
    }
}
$env:JAVA_HOME = $jdkDir
Write-Host "    JDK: $jdkDir" -ForegroundColor Green

# ---------------- 2. Android SDK ----------------
Write-Step "检查 Android SDK commandline-tools"
$sdkDir = Join-Path $TOOLS 'android-sdk'
$sdkManager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'

if (-not (Test-Path -LiteralPath $sdkManager)) {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'))) {
        $sdkDir = $env:ANDROID_HOME
        $sdkManager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
        Write-Host "    使用已有 ANDROID_HOME: $sdkDir"
    }
    else {
        $zip = Join-Path $TOOLS 'cmdline-tools.zip'
        $tmp = Join-Path $TOOLS '_tmp_sdk'
        Get-RemoteFile $SDK_URL $zip 100

        Remove-Safe $tmp
        Expand-ZipTo $zip $tmp

        $src = Join-Path $tmp 'cmdline-tools'
        if (-not (Test-Path -LiteralPath $src)) {
            throw "解压后没找到 cmdline-tools 目录，zip 可能不完整，删掉 $zip 重跑"
        }

        $target = Join-Path $sdkDir 'cmdline-tools\latest'
        Remove-Safe $target
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Move-Item -LiteralPath $src -Destination $target -Force

        Remove-Safe $tmp
        Remove-Safe $zip
    }
}
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
Write-Host "    SDK: $sdkDir" -ForegroundColor Green

# ---------------- 3. Gradle ----------------
Write-Step "检查 Gradle 8.7"
$gradleDir = Join-Path $TOOLS 'gradle-8.7'
$gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradleBat)) {
    $sys = Get-Command gradle -ErrorAction SilentlyContinue
    if ($sys) {
        $gradleBat = $sys.Source
        Write-Host "    使用系统 gradle: $gradleBat"
    }
    else {
        $zip = Join-Path $TOOLS 'gradle.zip'
        Get-RemoteFile $GRADLE_URL $zip 100
        Expand-ZipTo $zip $TOOLS
        Remove-Safe $zip
    }
}
$env:PATH = "$jdkDir\bin;$sdkDir\cmdline-tools\latest\bin;$gradleDir\bin;$env:PATH"
Write-Host "    Gradle: $gradleBat" -ForegroundColor Green

# ---------------- 4. 安装 SDK 组件 ----------------
Write-Step "接受 SDK 许可协议"
$yes = ("y`r`n" * 60)
$yes | & $sdkManager --licenses 2>&1 | Out-Null

Write-Step "安装 platform-tools / android-34 / build-tools 34.0.0"
& $sdkManager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
if ($LASTEXITCODE -ne 0) {
    throw "SDK 组件安装失败"
}

# ---------------- 5. 编译 ----------------
Write-Step "编译 APK（首次会下载 Gradle 依赖，约 5-10 分钟）"
& $gradleBat assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "编译失败，请把上面的报错发给我"
}

# ---------------- 6. 收尾 ----------------
$apkDir = Join-Path $ROOT 'app\build\outputs\apk\debug'
$apk = Get-ChildItem -LiteralPath $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
       Select-Object -First 1
if (-not $apk) {
    throw "没找到生成的 APK，编译可能没成功"
}

$desk = [Environment]::GetFolderPath('Desktop')
$target = Join-Path $desk 'VolumeSwipe-debug.apk'
Copy-Item -LiteralPath $apk.FullName -Destination $target -Force

Write-Host ""
Write-Host "=================================================" -ForegroundColor Green
Write-Host "  编译成功" -ForegroundColor Green
Write-Host "  APK: $target" -ForegroundColor Green
Write-Host "  把这个文件传到手机安装（需允许未知来源）" -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green
