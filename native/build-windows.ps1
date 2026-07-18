param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'binaries\windows-x86_64')
)

$ErrorActionPreference = 'Stop'

$cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
if (-not (Test-Path -LiteralPath $cargo)) {
    throw 'Rust cargo was not found under the current user profile.'
}

$mingw = Get-Command dlltool.exe -ErrorAction SilentlyContinue
if ($null -eq $mingw) {
    $wingetMingw = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages\BrechtSanders.WinLibs.MCF.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin'
    if (-not (Test-Path -LiteralPath (Join-Path $wingetMingw 'dlltool.exe'))) {
        throw 'MinGW dlltool.exe was not found.'
    }
    $env:Path = "$wingetMingw;$env:Path"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$targetRoot = Join-Path $env:TEMP 'reterraforged-quick-noise'
New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null

function Build-Package {
    param(
        [string]$Package,
        [string]$TargetDirectory,
        [string]$RustFlags
    )

    $env:CARGO_TARGET_DIR = $TargetDirectory
    $env:RUSTFLAGS = $RustFlags
    & $cargo build --release --locked --package $Package
    if ($LASTEXITCODE -ne 0) {
        throw "Cargo failed while building $Package."
    }
}

Build-Package 'reterraforged-quick-noise-dispatch' (Join-Path $targetRoot 'dispatch') ''
Copy-Item -Force -LiteralPath (Join-Path $targetRoot 'dispatch\release\reterraforged_quick_noise_dispatch.dll') -Destination $OutputDirectory

$variants = @(
    @{ Name = 'scalar'; Flags = '' },
    @{ Name = 'avx2'; Flags = '-C target-feature=+avx2,+fma' }
)
foreach ($variant in $variants) {
    $targetDirectory = Join-Path $targetRoot $variant.Name
    Build-Package 'reterraforged-quick-noise' $targetDirectory $variant.Flags
    $source = Join-Path $targetDirectory 'release\reterraforged_quick_noise.dll'
    $destination = Join-Path $OutputDirectory "reterraforged_quick_noise_$($variant.Name).dll"
    Copy-Item -Force -LiteralPath $source -Destination $destination
}

Remove-Item Env:RUSTFLAGS -ErrorAction SilentlyContinue
& (Join-Path $PSScriptRoot 'verify-windows-backends.ps1') -BinaryDirectory $OutputDirectory
if ($LASTEXITCODE -ne 0) {
    throw 'Scalar/AVX2 parity verification failed.'
}
Write-Output "Built ReTerraForged quick-noise natives in $OutputDirectory"
