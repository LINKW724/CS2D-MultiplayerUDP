param(
    [string]$Version = "3.1.3",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$clientRoot = Join-Path $repoRoot "client"
$serverRoot = Join-Path $repoRoot "server"
$releaseRoot = Join-Path $repoRoot "release"
$previewRoot = Join-Path $releaseRoot ("preview-v" + $Version)

function Invoke-MavenBuild([string]$ProjectRoot) {
    $arguments = @("-q", "clean", "package")
    if ($SkipTests) {
        $arguments += "-DskipTests"
    }
    & mvn @arguments -f (Join-Path $ProjectRoot "pom.xml")
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed: $ProjectRoot"
    }
}

function Reset-GeneratedDirectory([string]$Path, [string]$AllowedParent) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $resolvedParent = (Resolve-Path -LiteralPath $AllowedParent).Path
    if (-not $resolvedPath.StartsWith($resolvedParent + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected path: $resolvedPath"
    }
    Remove-Item -LiteralPath $resolvedPath -Recurse -Force
}

New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
Reset-GeneratedDirectory -Path $previewRoot -AllowedParent $releaseRoot
New-Item -ItemType Directory -Path $previewRoot | Out-Null

Invoke-MavenBuild -ProjectRoot $clientRoot
Invoke-MavenBuild -ProjectRoot $serverRoot

$clientImage = Join-Path $clientRoot "target\dist\CS2D Client"
if (-not (Test-Path -LiteralPath (Join-Path $clientImage "CS2D Client.exe"))) {
    throw "Client application image was not generated"
}
Copy-Item -LiteralPath (Join-Path $clientRoot "cs2d_settings.json") -Destination $clientImage
Copy-Item -LiteralPath (Join-Path $clientRoot "gamesettings.json") -Destination $clientImage

$serverInput = Join-Path $serverRoot "target\jpackage-input"
$serverDist = Join-Path $serverRoot "target\dist"
New-Item -ItemType Directory -Path $serverInput -Force | Out-Null
Reset-GeneratedDirectory -Path $serverDist -AllowedParent (Join-Path $serverRoot "target")
New-Item -ItemType Directory -Path $serverDist | Out-Null
Copy-Item -LiteralPath (Join-Path $serverRoot "target\cs2d-server.jar") -Destination $serverInput

& jpackage --type app-image `
    --name "CS2D Server" `
    --app-version $Version `
    --input $serverInput `
    --main-jar "cs2d-server.jar" `
    --main-class "cs2d.ServerMain" `
    --dest $serverDist
if ($LASTEXITCODE -ne 0) {
    throw "Server jpackage build failed"
}

$serverImage = Join-Path $serverDist "CS2D Server"
if (-not (Test-Path -LiteralPath (Join-Path $serverImage "CS2D Server.exe"))) {
    throw "Server application image was not generated"
}
Copy-Item -LiteralPath (Join-Path $serverRoot "maps") -Destination $serverImage -Recurse
Copy-Item -LiteralPath (Join-Path $serverRoot "public") -Destination $serverImage -Recurse
Copy-Item -LiteralPath (Join-Path $serverRoot "cs2d_settings.json") -Destination $serverImage

$clientZip = Join-Path $previewRoot ("CS2D-MultiplayerUDP-client-portable-v" + $Version + ".zip")
$serverZip = Join-Path $previewRoot ("CS2D-MultiplayerUDP-server-portable-v" + $Version + ".zip")
Compress-Archive -LiteralPath $clientImage -DestinationPath $clientZip -CompressionLevel Optimal
Compress-Archive -LiteralPath $serverImage -DestinationPath $serverZip -CompressionLevel Optimal

$checksums = @($clientZip, $serverZip) | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
    $hash.Hash.ToLowerInvariant() + "  " + (Split-Path -Leaf $_)
}
Set-Content -LiteralPath (Join-Path $previewRoot "SHA256SUMS.txt") -Value $checksums -Encoding utf8

Get-ChildItem -LiteralPath $previewRoot -File |
    Select-Object Name, Length, LastWriteTime
