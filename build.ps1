$ErrorActionPreference = "Continue"

$sdk = "D:\lib\android-sdk"
$bt = "$sdk\android-14"
$jar = "$sdk\android-35\android.jar"
$app = "D:\lib\rayneotv2-diag-app"
$libs = @(
    "$app\libs\api-13.1.5-classes.jar",
    "$app\libs\provider-13.1.5-classes.jar",
    "$app\libs\aidl-13.1.5-classes.jar",
    "$app\libs\shared-13.1.5-classes.jar"
) | Where-Object { Test-Path -LiteralPath $_ }

$env:JAVA_HOME = "C:\Users\hyl\.jdks\corretto-17.0.12"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step 失败，退出码 $LASTEXITCODE" }
}

Write-Output "1/6 aapt2 link"
& "$bt\aapt2.exe" link -o "$app\app.unsigned.apk" --manifest "$app\AndroidManifest.xml" `
    -I $jar --min-sdk-version 24 --target-sdk-version 34
Assert-Exit "aapt2 link"

Write-Output "2/6 javac"
if (Test-Path -LiteralPath "$app\classes") { Remove-Item -LiteralPath "$app\classes" -Recurse -Force }
New-Item -ItemType Directory -Path "$app\classes" | Out-Null
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar -nowarn `
    -classpath ($libs -join ";") -d "$app\classes" `
    (Get-ChildItem -LiteralPath "$app\src" -Filter *.java | ForEach-Object { $_.FullName }) 2>$null
Assert-Exit "javac"

Write-Output "3/6 d8"
if (Test-Path -LiteralPath "$app\dexout") { Remove-Item -LiteralPath "$app\dexout" -Recurse -Force }
New-Item -ItemType Directory -Path "$app\dexout" | Out-Null
$d8Inputs = @(Get-ChildItem -LiteralPath "$app\classes" -Filter *.class -Recurse | ForEach-Object { $_.FullName }) + $libs
& "$bt\d8.bat" --lib $jar --min-api 24 --output "$app\dexout" $d8Inputs
Assert-Exit "d8"

Write-Output "4/6 打包 dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$app\app.unsigned.apk", "Update")
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$app\dexout\classes.dex", "classes.dex", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

Write-Output "5/6 zipalign"
& "$bt\zipalign.exe" -f 4 "$app\app.unsigned.apk" "$app\app.aligned.apk"
Assert-Exit "zipalign"

Write-Output "6/6 签名"
if (-not (Test-Path -LiteralPath "$app\debug.keystore")) {
    & keytool -genkeypair -keystore "$app\debug.keystore" -alias rayneo -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname "CN=RayNeo Diag, OU=Dev, O=Local, L=Beijing, S=Beijing, C=CN"
}
& "$bt\apksigner.bat" sign --ks "$app\debug.keystore" --ks-pass pass:android --key-pass pass:android `
    --out "$app\RayNeoDiag.apk" "$app\app.aligned.apk"
Assert-Exit "apksigner"

Write-Output ("APK: $app\RayNeoDiag.apk  " + (Get-Item -LiteralPath "$app\RayNeoDiag.apk").Length + " bytes")
